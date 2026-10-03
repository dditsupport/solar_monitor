import com.dangeedums.solar.ble.*
import com.dangeedums.solar.cloud.*
import com.dangeedums.solar.data.CloudSessionStore
import com.dangeedums.solar.sync.DeviceSyncer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class DeviceSyncerTest {
    private fun csv(n: Int, v3: Boolean = true) = (1..n).joinToString("") { i ->
        "$i,4,${i * 900},231.50,1.200,250.00,${1000 + i}.00,0.990,50.01" + (if (v3) ",${1791000000 + i * 900}" else "") + "\n"
    }
    private fun info(n: Int) = DeviceInfoBle(deviceId = "solar-abc", fw = "1.1.0", unsyncedCount = n, currentBootId = 4,
                                             uptimeSec = 5000, heapFree = 90000, heapLargest = 40000, heapMin = 80000,
                                             seqFresh = false)

    @Test fun `big backlog goes up in 500-row chunks and is acked once`() = runBlocking {
        val gatt = SolarGatt(info(1234), emptyList(), csv(1234))
        val cloud = CloudClient()
        val r = DeviceSyncer(cloud, CloudSessionStore()).syncConnected(gatt)
        assertEquals(DeviceSyncer.Result.Synced(1234, 1234), r)
        assertEquals(listOf(500, 500, 234), cloud.posts.map { it.readings.size })
        assertEquals(1234L, gatt.acked)
        // one sync_wall_time for every chunk (captured with the uptime), heap only once
        assertEquals(1, cloud.posts.map { it.syncWallTime }.toSet().size)
        assertEquals(listOf("ble", null, null), cloud.posts.map { it.heapSource })
        assertEquals(listOf(90000L, null, null), cloud.posts.map { it.heapFree })
    }

    @Test fun `rows carry their timestamp and anything but ten fields is dropped`() = runBlocking {
        val cloud = CloudClient()
        DeviceSyncer(cloud, CloudSessionStore()).syncConnected(SolarGatt(info(3), emptyList(), csv(3)))
        assertEquals(listOf(1791000900L, 1791001800L, 1791002700L), cloud.posts[0].readings.map { it.t })
        // 9-field rows are not a format any firmware writes now: nothing to send
        val cloud2 = CloudClient()
        val r = DeviceSyncer(cloud2, CloudSessionStore()).syncConnected(SolarGatt(info(3), emptyList(), csv(3, v3 = false)))
        assertEquals(DeviceSyncer.Result.NothingPending, r)
        assertTrue(cloud2.posts.isEmpty())
        // unknown clock (epoch 0) -> null, not 0
        val cloud3 = CloudClient()
        DeviceSyncer(cloud3, CloudSessionStore()).syncConnected(SolarGatt(info(1), emptyList(), "1,4,900,231.5,1.2,250,1001,0.99,50,0\n"))
        assertNull(cloud3.posts[0].readings[0].t)
    }

    @Test fun `failure midway acks only what the server accepted`() = runBlocking {
        val gatt = SolarGatt(info(1234), emptyList(), csv(1234))
        val r = DeviceSyncer(CloudClient(acceptChunks = 2), CloudSessionStore()).syncConnected(gatt)
        assertTrue(r is DeviceSyncer.Result.Failed && "1000 of 1234" in r.message, r.toString())
        assertEquals(1000L, gatt.acked)
    }

    @Test fun `network exception midway also keeps the accepted part`() = runBlocking {
        val gatt = SolarGatt(info(1234), emptyList(), csv(1234))
        val r = DeviceSyncer(CloudClient(acceptChunks = 1, throwOnFail = true), CloudSessionStore()).syncConnected(gatt)
        assertTrue(r is DeviceSyncer.Result.Failed && "500 of 1234" in r.message, r.toString())
        assertEquals(500L, gatt.acked)
    }

    @Test fun `nothing accepted means no ack at all`() = runBlocking {
        val gatt = SolarGatt(info(10), emptyList(), csv(10))
        val r = DeviceSyncer(CloudClient(acceptChunks = 0), CloudSessionStore()).syncConnected(gatt)
        assertTrue(r is DeviceSyncer.Result.Failed, r.toString())
        assertNull(gatt.acked)
    }

    @Test fun `flash-erased device is renumbered above the server and nothing is lost`() = runBlocking {
        // server already holds seqs up to 5000 from the board's first life
        val cloud = CloudClient(serverMaxSeq = 5000)
        val gatt = SolarGatt(info(3).copy(seqFresh = true), emptyList(), csv(3))
        val r = DeviceSyncer(cloud, CloudSessionStore()).syncConnected(gatt)
        assertEquals(listOf(5000L), gatt.seqBases)
        assertEquals(DeviceSyncer.Result.Synced(3, 5003), r)
        assertEquals(listOf(5001L, 5002L, 5003L), cloud.posts.single().readings.map { it.seq })
        assertEquals(listOf(5003L), gatt.acks)        // no ack before the renumber: nothing was accepted
    }

    @Test fun `clash mid-way acks the accepted chunks first, then renumbers the rest`() = runBlocking {
        val cloud = CloudClient(clashFromChunk = 1)   // chunk 1 accepted, chunk 2 clashes
        val gatt = SolarGatt(info(1234), emptyList(), csv(1234))
        val r = DeviceSyncer(cloud, CloudSessionStore()).syncConnected(gatt)
        assertEquals(500L, gatt.acks.first())         // chunk 1 acked in the OLD numbering
        assertEquals(listOf(500L), gatt.seqBases)     // then shifted above the server max
        assertTrue(r is DeviceSyncer.Result.Synced, r.toString())
        val seqs = cloud.posts.flatMap { p -> p.readings.map { it.seq } }
        assertEquals(1234, seqs.size)                 // every row reached the server once
        assertEquals(seqs.size, seqs.toSet().size)    // with no duplicate numbers
    }

    @Test fun `server still refusing after the renumber gives up instead of looping`() = runBlocking {
        val cloud = CloudClient(serverMaxSeq = 10)
        val gatt = object : SolarGatt(info(2).copy(seqFresh = true), emptyList(), csv(2)) {
            override suspend fun setSeqBase(base: Long) = CommandResult("seq_base", true)   // device ignores it
        }
        val r = DeviceSyncer(cloud, CloudSessionStore()).syncConnected(gatt)
        assertTrue(r is DeviceSyncer.Result.Failed, r.toString())
        assertTrue(gatt.acks.isEmpty())
    }

    @Test fun `progress can be passed as a trailing lambda, as DeviceDetailViewModel does`() = runBlocking {
        // Compile-time guard: a parameter added after onProgress silently
        // captures this lambda and breaks the app's call site.
        val seen = mutableListOf<DeviceSyncer.Progress>()
        val r = DeviceSyncer(CloudClient(), CloudSessionStore())
            .syncConnected(SolarGatt(info(3), emptyList(), csv(3)), trustUnsyncedCount = false) { p -> seen += p }
        assertTrue(r is DeviceSyncer.Result.Synced, r.toString())
        assertTrue(seen.first() is DeviceSyncer.Progress.Reading && seen.last() is DeviceSyncer.Progress.Acking, seen.toString())
    }

    @Test fun `Wi-Fi status JSON key saved_ssid maps to savedSsid`() {
        val st = json.decodeFromString(WifiStatus.serializer(), """{"status":"connected","ssid":"A","saved_ssid":"Home"}""")
        assertEquals("Home", st.savedSsid)
    }

    // The Kotlin property names are camelCase; the JSON keys must stay exactly
    // what the PHP API reads and writes.
    @Test fun `cloud models keep the API's JSON keys`() {
        val payload = IngestPayload(
            deviceId = "solar-abc", fwVersion = "1.1.0", syncWallTime = "2026-10-03T10:00:00+05:30",
            currentBootId = 4, currentBootUptimeSec = 5000, bootHistory = listOf(IngestBoot(3, 600)),
            readings = listOf(IngestReading(seq = 1, bootId = 4, sec = 900, voltage = 231.5, current = 1.2,
                power = 250.0, energyWh = 1001.0, powerFactor = 0.99, frequency = 50.01, t = 1791000900)),
            seqFresh = true, heapFree = 1, heapLargest = 2, heapMin = 3, heapSource = "ble",
        )
        val sent = json.parseToJsonElement(json.encodeToString(IngestPayload.serializer(), payload)).jsonObject
        assertEquals(setOf("device_id", "fw_version", "sync_wall_time", "current_boot_id", "current_boot_uptime_sec",
                           "boot_history", "readings", "seq_fresh", "heap_free", "heap_largest", "heap_min",
                           "heap_source"), sent.keys)
        assertEquals(setOf("boot_id", "duration_sec"), sent["boot_history"]!!.jsonArray[0].jsonObject.keys)
        assertEquals(setOf("seq", "boot_id", "sec", "V", "I", "P", "Wh", "PF", "Hz", "t"),
                     sent["readings"]!!.jsonArray[0].jsonObject.keys)

        val resp = json.decodeFromString(IngestResponse.serializer(),
            """{"ok":false,"acked_up_to_seq":7,"server_time":"x","log_interval_sec":60,"error":"seq_base_required","seq_base":42}""")
        assertEquals(IngestResponse(false, 7, "x", 60, "seq_base_required", 42), resp)

        val rd = json.decodeFromString(ReadingsResponse.serializer(),
            """{"ok":true,"device_id":"d","friendly_name":"Roof","capacity_kw":8984.85,"adjustment_kwh":-1.5,
                "total_kwh":6.01,"points":[{"t":"a","t_end":"b","P":250,"P_avg":200,"P_peak":300,"V_avg":231}]}""")
        assertEquals(listOf("d", "Roof"), listOf(rd.deviceId, rd.friendlyName))
        assertEquals(listOf(8984.85, -1.5, 6.01), listOf(rd.capacityKw, rd.adjustmentKwh, rd.totalKwh))
        val pt = rd.points.single()
        assertEquals(listOf(250.0, 200.0, 300.0, 231.0), listOf(pt.power, pt.powerAvg, pt.powerPeak, pt.voltageAvg))
        assertEquals("b", pt.tEnd)

        val dev = json.decodeFromString(DevicesResponse.serializer(),
            """{"ok":true,"devices":[{"device_id":"d","friendly_name":"Roof","log_interval_sec":900}]}""").devices.single()
        assertEquals(listOf<Any?>("d", "Roof", 900), listOf(dev.deviceId, dev.friendlyName, dev.logIntervalSec))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
