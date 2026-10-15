package com.kadaiyare.izuna.data.listenbrainz

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ListenBrainzConnection(val root: String, val token: String, val account: String, val enabledAtMs: Long = 0) {
    val id: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$root\n$account".toByteArray()).joinToString("") { "%02x".format(it) }
    fun enabled(at: Long) = ListenBrainzConnection(root, token, account, at)
    override fun toString() = "ListenBrainzConnection(token=redacted)"
}

internal interface ListenBrainzStore {
    fun read(): ListenBrainzConnection?
    fun write(connection: ListenBrainzConnection?)
}

internal class EncryptedListenBrainzStore(file: File, private val alias: String = "izuna.listenbrainz.auth.v1") : ListenBrainzStore {
    private val file = AtomicFile(file)
    private val header = byteArrayOf(73, 90, 76, 66, 1)
    override fun read(): ListenBrainzConnection? {
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { it.readNBytes(8193) }
        require(bytes.size in 34..8192 && bytes.copyOfRange(0, 5).contentEquals(header))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(5, 17)))
        cipher.updateAAD(header)
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(bytes.copyOfRange(17, bytes.size)))).use {
            ListenBrainzConnection(it.readUTF(), it.readUTF(), it.readUTF(), it.readLong()).also { connection ->
                require(it.available() == 0 && connection.root == listenBrainzRoot(connection.root) &&
                    validListenBrainzToken(connection.token) && connection.account.isNotBlank() &&
                    connection.account.length <= 256 && connection.enabledAtMs >= 0)
            }
        }
    }
    override fun write(connection: ListenBrainzConnection?) {
        if (connection == null) { file.delete(); check(!file.baseFile.exists()); return }
        require(connection.root == listenBrainzRoot(connection.root) && validListenBrainzToken(connection.token) &&
            connection.account.isNotBlank() && connection.account.length <= 256 && connection.enabledAtMs >= 0)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeUTF(connection.root); it.writeUTF(connection.token); it.writeUTF(connection.account); it.writeLong(connection.enabledAtMs)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(header)
        val bytes = header + cipher.iv + cipher.doFinal(buffer.toByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        check(file.baseFile.readBytes().contentEquals(bytes))
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
