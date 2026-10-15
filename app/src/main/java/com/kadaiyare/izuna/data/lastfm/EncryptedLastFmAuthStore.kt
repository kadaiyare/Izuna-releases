package com.kadaiyare.izuna.data.lastfm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The repository serializes access; AtomicFile alone does not provide locking.
internal class EncryptedLastFmAuthStore(file: File, private val alias: String) : LastFmAuthStore {
    private val file = AtomicFile(file)
    private val header = byteArrayOf(73, 90, 76, 70, 2)

    override fun read(): StoredLastFmAuth {
        if (!file.baseFile.exists()) return StoredLastFmAuth.Empty
        val bytes = file.openRead().use { it.readNBytes(8_193) }
        if (bytes.size !in 34..8_192 || !bytes.take(4).toByteArray().contentEquals(header.take(4).toByteArray()) || bytes[4].toInt() !in 1..2) {
            throw IOException("Invalid authentication storage")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(5, 17)))
        cipher.updateAAD(bytes.copyOfRange(0, 5))
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(bytes.copyOfRange(17, bytes.size)))).use { input ->
            val auth = when (input.readByte().toInt()) {
                1 -> StoredLastFmAuth.Pending(input.readUTF(), input.readLong(), input.readUTF()).also {
                    require(it.token.isLastFmKey() && it.apiKey.isLastFmKey() && it.createdAtMs > 0)
                }
                2 -> StoredLastFmAuth.Connected(input.readUTF(), input.readUTF(), input.readUTF(), if (bytes[4].toInt() >= 2) input.readLong() else 0).also {
                    require(it.accountName.isNotBlank() && it.accountName.length <= 256 && it.sessionKey.isLastFmKey() && it.apiKey.isLastFmKey() && it.enabledAtMs >= 0)
                }
                else -> throw IOException("Unknown authentication storage")
            }
            if (input.available() != 0) throw IOException("Invalid authentication storage")
            auth
        }
    }

    override fun write(auth: StoredLastFmAuth) {
        if (auth == StoredLastFmAuth.Empty) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Authentication storage could not be cleared")
            return
        }
        when (auth) {
            is StoredLastFmAuth.Pending -> require(auth.token.isLastFmKey() && auth.apiKey.isLastFmKey() && auth.createdAtMs > 0)
            is StoredLastFmAuth.Connected -> require(auth.accountName.isNotBlank() && auth.accountName.length <= 256 && auth.sessionKey.isLastFmKey() && auth.apiKey.isLastFmKey() && auth.enabledAtMs >= 0)
            StoredLastFmAuth.Empty -> error("Already cleared")
        }
        val plain = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                when (auth) {
                    is StoredLastFmAuth.Pending -> {
                        output.writeByte(1); output.writeUTF(auth.token); output.writeLong(auth.createdAtMs); output.writeUTF(auth.apiKey)
                    }
                    is StoredLastFmAuth.Connected -> {
                        output.writeByte(2); output.writeUTF(auth.accountName); output.writeUTF(auth.sessionKey); output.writeUTF(auth.apiKey)
                        output.writeLong(auth.enabledAtMs)
                    }
                    StoredLastFmAuth.Empty -> error("Already cleared")
                }
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(plain)
        val bytes = header + cipher.iv + encrypted
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        if (!file.baseFile.readBytes().contentEquals(bytes)) throw IOException("Authentication storage write incomplete")
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Authentication encryption key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
