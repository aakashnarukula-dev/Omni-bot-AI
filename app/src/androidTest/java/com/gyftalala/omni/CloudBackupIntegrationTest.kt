package com.gyftalala.omni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.firebase.auth.GoogleAuthProvider
import android.util.Base64
import com.gyftalala.omni.backup.*
import com.gyftalala.omni.cloud.*
import com.gyftalala.omni.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CloudBackupIntegrationTest {
    private val context get() = TestSupport.context
    private val account = "cloud-portability"
    private val services get() = CloudServices.get(context)
    @Before fun guard() { TestSupport.requireIsolated(); assumeTrue(BuildConfig.DEBUG && BuildConfig.CLOUD_EMULATOR) }
    private fun http(path: String, body: JSONObject? = null): JSONObject {
        check(BuildConfig.CLOUD_EMULATOR)
        val connection = URL("http://127.0.0.1:9099/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            check(connection.responseCode in 200..299) { "Synthetic auth emulator request failed: ${connection.responseCode}" }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally { connection.disconnect() }
    }
    private suspend fun login(subject: String = account): String {
        check(BuildConfig.CLOUD_EMULATOR && BuildConfig.APPLICATION_ID == "com.gyftalala.omni.verification")
        services.auth.signOut()
        fun encoded(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val now = System.currentTimeMillis() / 1000
        val claims = JSONObject().put("iss", "https://accounts.google.com").put("aud", BuildConfig.CLOUD_WEB_CLIENT_ID)
            .put("sub", subject).put("email", "$subject@example.test").put("email_verified", true).put("iat", now).put("exp", now + 3600)
        val token = "${encoded("{\"alg\":\"none\"}")}.${encoded(claims.toString())}."
        // Unsigned provider tokens are accepted only by the disposable Firebase Auth emulator.
        services.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        assertNotNull(services.owner)
        return services.owner!!.uid
    }
    private fun seed(store: OmniStore) {
        val original = "PRIVATE SYNTHETIC CARD PHOTO BYTES".toByteArray()
        val attachment = store.vault.import(TestSupport.file("cloud-test.bin", original).second)
        store.save(Memory("cloud-card", "IndusInd debit card", "synthetic card", Category.CARD, files = listOf(attachment),
            card = CardDetails("IndusInd", "TEST OWNER", "4111111111111111", "09/29", "123"), privateToDevice = true))
        store.save(Memory("cloud-note", "Recovery test", "Synthetic backup survives data clearing", Category.NOTE))
        store.save(ChatMessage("cloud-chat", Role.USER, "Remember this test", 1L, attachmentId = "cloud-card"))
        store.saveSettings(JSONObject().put("key", "SYNTHETIC_KEY_MUST_NOT_TRAVEL").put("cloud", true))
    }
    private suspend fun upload(): RemoteBackup {
        val uid = services.auth.currentUser!!.uid
        val local = CloudLocal(context)
        val p = local.update { CloudPreferences(uid = uid, accountKeyReady = true, enabled = false, wifiOnly = false) }
        val worker = TestListenableWorkerBuilder<CloudBackupWorker>(context).setInputData(workDataOf(
            "uid" to uid, "scheduleId" to p.scheduleId, "daily" to false)).build()
        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertTrue(local.read().lastError, local.read().lastSuccess > 0)
        return CloudRepository(services).list(uid).first()
    }
    @Test fun workerEncryptsUploadsAndKeyStaysOutsidePortableBackup() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-account-24")
        OmniStore(context).use { seed(it) }
        val remote = upload()
        val ciphertext = File(context.cacheDir,"download.omnibak")
        try {
            CloudRepository(services).download(uid,remote,ciphertext)
            assertEquals("OMNIACC!",String(ciphertext.readBytes().take(8).toByteArray()))
            assertFalse(String(ciphertext.readBytes()).contains("4111111111111111"))
            val key = CloudAccountKey(context,services).get(uid)
            val manifest = ciphertext.inputStream().use { input -> AccountBackupCipher.decrypt(input,key,AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID,uid)) { stream, _ ->
                val data = DataInputStream(stream); val bytes = ByteArray(data.readInt()).also(data::readFully)
                while (data.read() != -1) { }
                String(bytes)
            } }
            assertFalse(manifest.contains(uid)); assertFalse(manifest.contains("SYNTHETIC_KEY_MUST_NOT_TRAVEL"))
            val localBytes = File(context.noBackupFilesDir,"cloud-backup-settings").readBytes()
            assertFalse(String(localBytes).contains(uid))
            val cached = File(context.noBackupFilesDir,"cloud-account-key").readBytes()
            assertFalse(String(cached).contains(Base64.encodeToString(key,Base64.NO_WRAP)))
            key.fill(0)
        } finally { ciphertext.delete() }
    }
    @Test fun automaticPhoneCopyIsDiscoveredWithoutRememberedUri() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-auto-phone-${System.nanoTime()}")
        OmniStore(context).use { seed(it) }; val remote = upload()
        val preferences = CloudLocal(context).read()
        assertTrue(preferences.localSuccess > 0); assertTrue(preferences.backupBytes > 0)
        assertEquals("Backup saved on phone and in cloud.", preferences.backupStatus)
        File(context.noBackupFilesDir, "mobile-backup-sources").delete()
        val owner = AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, uid)
        val phone = MobileBackupCatalog(context).scan(owner).files.single()
        assertEquals(CloudRepository(services).header(uid, remote).created, phone.created)
        val bytes = context.contentResolver.openInputStream(phone.uri)!!.use { it.readBytes() }
        assertEquals("OMNIACC!", String(bytes.take(8).toByteArray()))
        assertFalse(String(bytes).contains("SYNTHETIC_KEY_MUST_NOT_TRAVEL"))
        assertTrue(MobileBackupCatalog(context).scan(AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, "different-account")).files.isEmpty())
        TestSupport.reset()
        CloudLocal(context).update { CloudPreferences() }
        withController { controller, store ->
            controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
            assertTrue(controller.state.value.mobileCount > 0)
            controller.restore(); wait(controller) { it.step == CloudStep.DONE }
            assertEquals(2, store.memories().size)
            assertFalse(store.settings().toString().contains("SYNTHETIC_KEY_MUST_NOT_TRAVEL"))
        }
    }

    @Test fun manualBackupReportsProgressAndSavesBothCopies() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-manual-${System.nanoTime()}")
        CloudLocal(context).update { CloudPreferences(uid = uid, accountKeyReady = true, enabled = false, wifiOnly = false, onboardingSeen = true) }
        withController { controller, store ->
            seed(store)
            controller.refresh(); wait(controller) { it.ready && it.cloudChecked }
            controller.backUpNow()
            wait(controller) { it.lastSuccess > 0 && it.localSuccess > 0 && !it.backingUp }
            assertTrue(controller.state.value.backupBytes > 0)
            assertEquals("Backup saved on phone and in cloud.", controller.state.value.backupStatus)
            assertNull(controller.state.value.error)
        }
    }

    @Test fun offlineManualBackupKeepsLocalCopyAndReportsCloudWaiting() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-offline-${System.nanoTime()}")
        OmniStore(context).use { seed(it) }
        val p = CloudLocal(context).update { CloudPreferences(uid = uid, accountKeyReady = true, wifiOnly = false, onboardingSeen = true) }
        CloudAccountKey(context, services).get(uid).fill(0)
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        try {
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            withTimeout(10000) { while (connectivity.activeNetwork != null) delay(100) }
            val worker = TestListenableWorkerBuilder<CloudBackupWorker>(context).setInputData(workDataOf(
                "uid" to uid, "scheduleId" to p.scheduleId, "daily" to false)).build()
            assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
            assertTrue(CloudLocal(context).read().localSuccess > 0)
            assertEquals(0L, CloudLocal(context).read().lastSuccess)
            assertEquals("Saved on phone. Cloud backup will retry when connected.", CloudLocal(context).read().backupStatus)
            assertTrue(MobileBackupCatalog(context).scan(AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID, uid)).files.isNotEmpty())
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
            withTimeout(10000) { while (connectivity.activeNetwork == null) delay(100) }
        }
        assertTrue(CloudRepository(services).list(uid).isEmpty())
    }

    @Test fun accountSeparationAndUnknownAccountAreEnforced() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-account-25")
        OmniStore(context).use { seed(it) }; val remote = upload()
        val other = login("cloud-account-26")
        assertTrue(CloudRepository(services).list(other).isEmpty())
        assertTrue(runCatching { CloudRepository(services).list(uid) }.isFailure)
        assertTrue(runCatching { services.storage.reference.child("backups/$uid/${remote.name}").getBytes(1024*1024).await() }.isFailure)
    }
    @Test fun staleWorkerCannotUploadAfterAccountOrScheduleChange() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-account-27")
        val p = CloudLocal(context).update { CloudPreferences(uid = uid,accountKeyReady = true,enabled = true) }
        CloudLocal(context).update { it.copy(scheduleId = "changed") }
        val worker = TestListenableWorkerBuilder<CloudBackupWorker>(context).setInputData(workDataOf("uid" to uid,"scheduleId" to p.scheduleId,"daily" to true)).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(),worker.doWork())
        assertEquals(0,CloudLocal(context).read().lastSuccess)
        assertTrue(CloudRepository(services).list(uid).isEmpty())
    }
    @Test fun googleRecoveryNeedsOnlyExplicitConfirmationThenRestoresOriginals() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-account-28")
        OmniStore(context).use { seed(it) }; upload()
        TestSupport.reset(); CloudLocal(context).update { CloudPreferences() }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        OmniStore(context).use { store ->
            val controller = CloudBackupController(context,store,scope) { }
            try {
                controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
                assertTrue(controller.state.value.prompt); assertTrue(store.memories().isEmpty())
                assertEquals(2,controller.state.value.preview!!.items); assertTrue(store.memories().isEmpty())
                controller.restore(); wait(controller) { it.step == CloudStep.DONE }
                assertEquals(2,store.memories().size); assertEquals(1,store.messages().size)
                val card=store.memories().first { it.id=="cloud-card" }
                assertTrue(card.staysOnDevice); assertEquals("4111111111111111",card.card!!.number)
                assertEquals("PRIVATE SYNTHETIC CARD PHOTO BYTES",ByteArrayOutputStream().also { store.vault.export(card.files.single(),it) }.toString())
                assertEquals("",store.settings().optString("key")); assertFalse(store.settings().optBoolean("cloud"))
                val local=CloudLocal(context).read(); assertTrue(local.enabled); assertEquals(uid,local.uid)
                CloudBackupSchedule.cancel(context)
            } finally { controller.cancel(); delay(100); scope.cancel() }
        }
    }
    @Test fun backgroundDailyJobCompletesAndQueuesFollowingDay() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-account-29")
        OmniStore(context).use { seed(it) }
        val p = CloudLocal(context).update { CloudPreferences(uid = uid,accountKeyReady = true,enabled = true,wifiOnly = false) }
        val work = androidx.work.WorkManager.getInstance(context)
        val request = androidx.work.OneTimeWorkRequestBuilder<CloudBackupWorker>().setInputData(workDataOf(
            "uid" to uid,"scheduleId" to p.scheduleId,"daily" to true)).build()
        try {
            work.enqueueUniqueWork("omni-daily-cloud-backup",androidx.work.ExistingWorkPolicy.REPLACE,request).result.get()
            withTimeout(90000) { while (CloudLocal(context).read().lastSuccess == 0L) delay(200) }
            withTimeout(15000) { while (!work.getWorkInfoById(request.id).get().state.isFinished) delay(200) }
            val jobs = work.getWorkInfosForUniqueWork("omni-daily-cloud-backup").get()
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,jobs.first { it.id == request.id }.state)
            assertTrue(jobs.any { it.id != request.id && !it.state.isFinished })
            assertTrue(CloudRepository(services).list(uid).isNotEmpty())
        } finally { CloudBackupSchedule.cancel(context) }
    }

    @Test fun cancellingOneDailyTransferKeepsTheFollowingDayScheduled() = runBlocking {
        val p = CloudLocal(context).update { CloudPreferences(uid = "synthetic-cancel-owner", accountKeyReady = true, enabled = true) }
        val work = androidx.work.WorkManager.getInstance(context)
        val request = androidx.work.OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build()
        try {
            work.enqueueUniqueWork("omni-daily-cloud-backup", androidx.work.ExistingWorkPolicy.REPLACE, request).result.get()
            CloudBackupSchedule.cancelRun(context, request.id, p.uid, p.scheduleId, true)
            withTimeout(10000) {
                while (work.getWorkInfosForUniqueWork("omni-daily-cloud-backup").get().none { it.id != request.id && !it.state.isFinished }) delay(100)
            }
            // Enqueuing the next unique chain may prune the cancelled predecessor.
            val previous = work.getWorkInfoById(request.id).get()
            assertTrue(previous == null || previous.state == androidx.work.WorkInfo.State.CANCELLED)
        } finally { CloudBackupSchedule.cancel(context) }
    }

    private suspend fun withController(block: suspend (CloudBackupController, OmniStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        OmniStore(context).use { store ->
            val controller = CloudBackupController(context, store, scope) { }
            try { block(controller, store) }
            finally { controller.cancel(); delay(200); scope.cancel(); CloudBackupSchedule.cancel(context) }
        }
    }
    @Test fun newerPhoneBackupWinsAndOtherAccountFileIsExcluded() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-local-order")
        OmniStore(context).use { seed(it) }; upload()
        val key = CloudAccountKey(context,services).get(uid)
        val owner = AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID,uid)
        delay(20)
        val phone = ByteArrayOutputStream()
        OmniStore(context).use { store ->
            store.save(Memory("new-local", "Local only", "Newest snapshot", Category.NOTE))
            VaultBackup(context,store).writeAccount(phone,key,owner)
        }
        val foreign = ByteArrayOutputStream()
        AccountBackupCipher.encrypt(foreign,key,AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID,"other"),System.currentTimeMillis()+10000) { it.write(byteArrayOf(1)) }
        key.fill(0)
        TestSupport.reset(); CloudLocal(context).update { CloudPreferences() }
        val good = TestSupport.file("newer-phone.omnibak",phone.toByteArray()).second
        val bad = TestSupport.file("foreign.omnibak",foreign.toByteArray()).second
        MobileBackupCatalog(context).remember(listOf(good,bad))
        withController { controller, store ->
            controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
            assertTrue(controller.state.value.source.startsWith("Phone"))
            assertTrue(controller.state.value.mobileCount >= 2)
            assertFalse(controller.state.value.candidates.any { it.mobile?.uri == bad })
            assertEquals(3,controller.state.value.preview!!.items)
            assertTrue(store.memories().isEmpty())
            controller.restore(); wait(controller) { it.step == CloudStep.DONE }
            assertEquals(3,store.memories().size)
        }
    }
    @Test fun corruptNewestBackupFallsBackToVerifiedCloudCopy() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-corrupt-newer")
        OmniStore(context).use { seed(it) }; upload()
        val key = CloudAccountKey(context,services).get(uid)
        val broken = ByteArrayOutputStream()
        AccountBackupCipher.encrypt(broken,key,AccountBackupCipher.ownerTag(BuildConfig.CLOUD_PROJECT_ID,uid),System.currentTimeMillis()+10000) { it.write(byteArrayOf(1,2)) }
        key.fill(0)
        val temp = TestSupport.file("broken.omnibak",broken.toByteArray()).first
        CloudRepository(services).upload(uid,temp)
        TestSupport.reset(); CloudLocal(context).update { CloudPreferences() }
        withController { controller, store ->
            controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
            assertEquals("Cloud",controller.state.value.source); assertTrue(controller.state.value.ignored > 0)
            assertEquals(2,controller.state.value.preview!!.items); assertTrue(store.memories().isEmpty())
        }
    }
    @Test fun skipDoesNotRestoreOrReplaceCloudWithEmptyVault() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-skip")
        OmniStore(context).use { seed(it) }; upload()
        val before = CloudRepository(services).list(uid).map { it.name }
        TestSupport.reset(); CloudLocal(context).update { CloudPreferences() }
        withController { controller, store ->
            controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
            controller.skipRestore(); wait(controller) { it.enterApp }
            assertTrue(store.memories().isEmpty()); assertTrue(CloudLocal(context).read().onboardingSeen)
            CloudBackupSchedule.cancel(context)
            val p = CloudLocal(context).read()
            val worker = TestListenableWorkerBuilder<CloudBackupWorker>(context).setInputData(workDataOf("uid" to uid,"scheduleId" to p.scheduleId,"daily" to false)).build()
            assertEquals(androidx.work.ListenableWorker.Result.success(),worker.doWork())
            assertEquals(before,CloudRepository(services).list(uid).map { it.name })
            assertEquals(0L,CloudLocal(context).read().lastSuccess)
            assertTrue(CloudLocal(context).read().backupStatus.startsWith("Nothing to back up"))
        }
    }
    @Test fun cachedKeySurvivesRepeatLoginAndRepairsFromAccount() = runBlocking {
        val uid = login("cloud-key-cache")
        val keys = CloudAccountKey(context,services)
        val first = keys.get(uid,true)
        val second = keys.get(uid)
        assertArrayEquals(first,second)
        File(context.noBackupFilesDir,"cloud-account-key").writeBytes(byteArrayOf(1,2,3))
        assertArrayEquals(first,keys.get(uid))
        keys.clear(); assertArrayEquals(first,keys.get(uid,true))
        first.fill(0); second.fill(0)
    }

    @Test fun noBackupAccountCanFinishSetupWithoutPassword() = runBlocking {
        TestSupport.reset(); login("cloud-no-backup")
        CloudLocal(context).update { CloudPreferences() }
        File(context.noBackupFilesDir, "mobile-backup-sources").delete()
        withController { controller, store ->
            controller.signedIn(); wait(controller) { it.enterApp }
            assertNull(controller.state.value.preview); assertFalse(controller.state.value.onboarding)
            assertTrue(store.memories().isEmpty()); assertTrue(CloudLocal(context).read().enabled)
        }
    }
    @Test fun signOutClearsRecoveryCacheButKeepsLocalAndCloudItems() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-sign-out")
        OmniStore(context).use { seed(it) }; upload()
        withController { controller, store ->
            controller.signOut(); wait(controller) { it.step == CloudStep.HOME && it.status.startsWith("Signed out") }
            assertNull(services.owner); assertFalse(CloudLocal(context).read().enabled)
            assertFalse(File(context.noBackupFilesDir,"cloud-account-key").exists())
            assertEquals(2,store.memories().size)
            assertEquals(uid,login("cloud-sign-out")); assertTrue(CloudRepository(services).list(uid).isNotEmpty())
        }
    }
    @Test fun corruptPhoneCatalogDoesNotBlockCloudDiscovery() = runBlocking {
        TestSupport.reset(); login("cloud-bad-catalog")
        OmniStore(context).use { seed(it) }; upload()
        File(context.noBackupFilesDir,"mobile-backup-sources").writeBytes(byteArrayOf(1,2,3))
        CloudLocal(context).update { CloudPreferences() }
        withController { controller, _ ->
            controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
            assertEquals("Cloud",controller.state.value.source); assertTrue(controller.state.value.ignored > 0)
        }
        File(context.noBackupFilesDir,"mobile-backup-sources").delete(); Unit
    }

    @Test fun missingAccountKeyFailsClosedButSkipStillFinishesSetup() = runBlocking {
        TestSupport.reset(); val uid = login("cloud-missing-key")
        CloudBackupSchedule.cancel(context); CloudAccountKey(context,services).clear()
        CloudLocal(context).update { CloudPreferences() }
        val file = TestSupport.file("orphaned.omnibak",byteArrayOf(1,2,3)).first
        CloudRepository(services).upload(uid,file)
        withController { controller, store ->
            controller.signedIn()
            withTimeout(45000) { while (controller.state.value.step != CloudStep.HOME || controller.state.value.error == null) delay(100) }
            assertNull(controller.state.value.preview)
            assertFalse(File(context.noBackupFilesDir,"cloud-account-key").exists())
            controller.skipRestore(); wait(controller) { it.enterApp }
            assertTrue(CloudLocal(context).read().onboardingSeen); assertFalse(CloudLocal(context).read().enabled)
            assertTrue(store.memories().isEmpty()); assertTrue(CloudRepository(services).list(uid).isNotEmpty())
        }
    }

    private suspend fun wait(controller: CloudBackupController, condition: (CloudState)->Boolean) {
        withTimeout(45000) { while (!condition(controller.state.value)) {
            check(controller.state.value.step != CloudStep.HOME || controller.state.value.error == null) { controller.state.value.toString() }
            delay(100)
        } }
    }
    @Test fun portabilitySeed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudPhase") == "seed")
        TestSupport.reset(); login(); OmniStore(context).use { seed(it) }; upload(); Unit
    }
    @Test fun portabilityRecoverAfterReinstall() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudPhase") == "recover")
        assertFalse(CloudLocal(context).read().configured)
        OmniStore(context).use { assertTrue(it.memories().isEmpty()) }
        login()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        OmniStore(context).use { store ->
            val controller=CloudBackupController(context,store,scope) { }
            try {
                controller.signedIn(); wait(controller) { it.step == CloudStep.AVAILABLE }
                assertTrue(controller.state.value.prompt)
                assertTrue(store.memories().isEmpty())
                controller.restore(); wait(controller) { it.step == CloudStep.DONE }
                assertEquals(2,store.memories().size)
                assertEquals("4111111111111111",store.memories().first { it.id=="cloud-card" }.card!!.number)
                assertTrue(CloudLocal(context).read().enabled)
                CloudBackupSchedule.cancel(context)
            } finally { controller.cancel(); delay(100); scope.cancel() }
        }
    }
}
