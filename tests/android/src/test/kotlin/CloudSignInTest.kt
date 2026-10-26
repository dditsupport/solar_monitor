import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.cloud.CloudSignIn
import com.dangeedums.solar.cloud.CloudSignIn.Status
import com.dangeedums.solar.data.CloudCredentialStore
import com.dangeedums.solar.data.CloudCredentialStore.Credentials
import com.dangeedums.solar.data.CloudSessionStore
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** The check every sync runs before it reads anything off a device. */
class CloudSignInTest {
    private val saved = Credentials("dipak", "pw")

    @Test fun `a live session is used as is`() = runBlocking {
        val cloud = CloudClient()
        assertEquals(Status.SignedIn, CloudSignIn(cloud, CloudCredentialStore(saved), CloudSessionStore()).ensureSignedIn())
        assertTrue(cloud.logins.isEmpty())
        assertEquals("tok", cloud.csrf, "CSRF token loaded for the upload")
    }

    @Test fun `an expired session is renewed with the saved sign-in`() = runBlocking {
        val cloud = CloudClient().apply { sessionAlive = false }
        assertEquals(Status.SignedIn, CloudSignIn(cloud, CloudCredentialStore(saved), CloudSessionStore()).ensureSignedIn())
        assertEquals(listOf("dipak"), cloud.logins)
    }

    @Test fun `no saved sign-in means no sync`() = runBlocking {
        val cloud = CloudClient().apply { sessionAlive = false }
        assertEquals(Status.NeedsSignIn, CloudSignIn(cloud, CloudCredentialStore(null), CloudSessionStore()).ensureSignedIn())
        assertTrue(cloud.logins.isEmpty())
    }

    @Test fun `a rejected saved password is forgotten`() = runBlocking {
        val cloud = CloudClient().apply { sessionAlive = false; serverPassword = "changed" }
        val creds = CloudCredentialStore(saved)
        assertEquals(Status.NeedsSignIn, CloudSignIn(cloud, creds, CloudSessionStore()).ensureSignedIn())
        assertNull(creds.saved, "not retried on every sync")
    }

    @Test fun `no network is reported as unreachable`() = runBlocking {
        val cloud = CloudClient().apply { reachable = false }
        assertEquals(Status.Unreachable, CloudSignIn(cloud, CloudCredentialStore(saved), CloudSessionStore()).ensureSignedIn())
    }

    @Test fun `a device token needs no session`() = runBlocking {
        val cloud = CloudClient().apply { reachable = false }
        assertEquals(Status.SignedIn, CloudSignIn(cloud, CloudCredentialStore(null), CloudSessionStore("devtoken")).ensureSignedIn())
    }
}
