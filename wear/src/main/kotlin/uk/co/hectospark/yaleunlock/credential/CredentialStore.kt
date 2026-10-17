package uk.co.hectospark.yaleunlock.credential

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the single lock credential encrypted at rest.
 *
 * - AES-256-GCM under a non-exportable Android Keystore key (StrongBox when
 *   the watch has it), usable only while the watch is unlocked.
 * - The ciphertext lives in `noBackupFilesDir`; backup and device transfer
 *   are disabled in the manifest as well.
 * - Nothing here logs credential material; failures log only exception types.
 */
class CredentialStore private constructor(private val context: Context) {

    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val _provisioned = MutableStateFlow(file.baseFile.exists())

    /** Whether a credential is stored. Never exposes the credential itself. */
    val provisioned: StateFlow<Boolean> = _provisioned.asStateFlow()

    sealed interface LoadResult {
        data class Loaded(val credential: LockCredential) : LoadResult
        data object Missing : LoadResult

        /** The Keystore refused: typically the watch is locked (off-wrist). */
        data object Unavailable : LoadResult

        /** Stored data is unreadable (e.g. the Keystore key was invalidated). */
        data object Corrupt : LoadResult
    }

    @Synchronized
    fun save(credential: LockCredential) {
        val plain = encode(credential)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            val sealed = cipher.doFinal(plain)
            val out = file.startWrite()
            try {
                DataOutputStream(out).apply {
                    writeByte(FORMAT_VERSION)
                    writeByte(cipher.iv.size)
                    write(cipher.iv)
                    write(sealed)
                    flush()
                }
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        } finally {
            plain.fill(0)
        }
        _provisioned.value = true
    }

    @Synchronized
    fun load(): LoadResult {
        if (!file.baseFile.exists()) return LoadResult.Missing
        val key = try {
            keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return LoadResult.Corrupt
        } catch (e: Exception) {
            Log.w(TAG, "Keystore lookup failed: ${e.javaClass.simpleName}")
            return LoadResult.Unavailable
        }
        var plain: ByteArray? = null
        return try {
            val bytes = file.readFully()
            val input = DataInputStream(bytes.inputStream())
            if (input.readUnsignedByte() != FORMAT_VERSION) return LoadResult.Corrupt
            val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val sealed = input.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            plain = cipher.doFinal(sealed)
            LoadResult.Loaded(decode(plain))
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            LoadResult.Corrupt
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            LoadResult.Unavailable
        } catch (e: java.security.InvalidKeyException) {
            // Raised for "device locked" with setUnlockedDeviceRequired.
            Log.w(TAG, "Credential key unavailable: ${e.javaClass.simpleName}")
            LoadResult.Unavailable
        } catch (e: Exception) {
            Log.w(TAG, "Credential unreadable: ${e.javaClass.simpleName}")
            LoadResult.Corrupt
        } finally {
            plain?.fill(0)
        }
    }

    /** Removes the ciphertext and the Keystore key. */
    @Synchronized
    fun delete() {
        file.delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
            .onFailure { Log.w(TAG, "Keystore delete failed: ${it.javaClass.simpleName}") }
        _provisioned.value = false
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generateKey(strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** Compact binary encoding so no String ever holds the key. */
    private fun encode(c: LockCredential): ByteArray {
        val key = c.keyCopy()
        val buffer = ByteArrayOutputStream()
        try {
            DataOutputStream(buffer).apply {
                write(key)
                writeByte(c.slot)
                writeUTF(c.localName.orEmpty())
                writeUTF(c.address.orEmpty())
            }
            return buffer.toByteArray()
        } finally {
            key.fill(0)
        }
    }

    private fun decode(plain: ByteArray): LockCredential {
        val input = DataInputStream(plain.inputStream())
        val key = ByteArray(LockCredential.KEY_LEN).also(input::readFully)
        try {
            val slot = input.readUnsignedByte()
            val name = input.readUTF().ifEmpty { null }
            val address = input.readUTF().ifEmpty { null }
            return LockCredential(key, slot, name, address)
        } finally {
            key.fill(0)
        }
    }

    companion object {
        private const val TAG = "CredentialStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yale_lock_credential_v1"
        private const val FILE_NAME = "lock_credential.bin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1
        private val AAD = "uk.co.hectospark.yaleunlock/credential/v1".toByteArray()

        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CredentialStore? = null

        fun get(context: Context): CredentialStore =
            instance ?: synchronized(this) {
                instance ?: CredentialStore(context.applicationContext).also { instance = it }
            }
    }
}
