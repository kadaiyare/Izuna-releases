package com.kadaiyare.izuna

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.lastfm.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastFmAuthStoreTest {
    private fun withStore(block: (File, String, EncryptedLastFmAuthStore) -> Unit) {
        val id = UUID.randomUUID().toString()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "auth-test-$id")
        val alias = "izuna.auth.test.$id"
        try { block(file, alias, EncryptedLastFmAuthStore(file, alias)) }
        finally {
            AtomicFile(file).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun pendingAndSessionSurviveReopeningWithoutPlaintextAndUseFreshNonces() = withStore { file, alias, store ->
        assertEquals(StoredLastFmAuth.Empty, store.read())
        store.write(StoredLastFmAuth.Pending("1".repeat(32), 1234, "0".repeat(32)))
        val pending = EncryptedLastFmAuthStore(file, alias).read() as StoredLastFmAuth.Pending
        assertEquals(1234L, pending.createdAtMs)
        assertEquals("1".repeat(32), pending.token)
        val connected = StoredLastFmAuth.Connected("テスト-user", "3".repeat(32), "0".repeat(32))
        store.write(connected)
        val first = file.readBytes()
        store.write(connected)
        assertFalse(first.contentEquals(file.readBytes()))
        val plain = file.readBytes().toString(Charsets.UTF_8)
        assertFalse(plain.contains(connected.accountName))
        assertFalse(plain.contains(connected.sessionKey))
        val reopened = EncryptedLastFmAuthStore(file, alias).read() as StoredLastFmAuth.Connected
        assertEquals(connected.accountName, reopened.accountName)
        assertEquals(connected.sessionKey, reopened.sessionKey)
        store.write(StoredLastFmAuth.Empty)
        assertFalse(file.exists())
        assertEquals(StoredLastFmAuth.Empty, store.read())
    }

    @Test fun tamperedStorageCannotBeUsedAndCanBeReset() = withStore { file, _, store ->
        store.write(StoredLastFmAuth.Pending("1".repeat(32), 1234, "0".repeat(32)))
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { store.read() }
        store.write(StoredLastFmAuth.Empty)
        assertEquals(StoredLastFmAuth.Empty, store.read())
    }

    @Test fun missingKeystoreKeyDoesNotGenerateAnUnreadableReplacementDuringRead() = withStore { _, alias, store ->
        store.write(StoredLastFmAuth.Pending("1".repeat(32), 1234, "0".repeat(32)))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(Exception::class.java) { store.read() }
        assertFalse(keys.containsAlias(alias))
        store.write(StoredLastFmAuth.Empty)
        store.write(StoredLastFmAuth.Connected("test-user", "3".repeat(32), "0".repeat(32)))
        assertTrue(store.read() is StoredLastFmAuth.Connected)
    }

    @Test fun consentIsEncryptedAndLegacySessionsRemainDisabled() = withStore { file, alias, store ->
        store.write(StoredLastFmAuth.Connected("test-user", "3".repeat(32), "0".repeat(32), 9876))
        assertEquals(9876L, (store.read() as StoredLastFmAuth.Connected).enabledAtMs)
        val header = byteArrayOf(73, 90, 76, 70, 1)
        val plain = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use { output ->
                output.writeByte(2); output.writeUTF("legacy-user"); output.writeUTF("3".repeat(32)); output.writeUTF("0".repeat(32))
            }
        }.toByteArray()
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key); updateAAD(header)
        }
        file.writeBytes(header + cipher.iv + cipher.doFinal(plain))
        val legacy = store.read() as StoredLastFmAuth.Connected
        assertEquals("legacy-user", legacy.accountName)
        assertEquals(0L, legacy.enabledAtMs)
        store.write(legacy)
        assertEquals(2, file.readBytes()[4].toInt())
        assertEquals(0L, (store.read() as StoredLastFmAuth.Connected).enabledAtMs)
    }
}
