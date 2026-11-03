package it.osmus.rx.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteRequestGateTest {
    @Test fun atMostOneRemoteRequestRunsAtATime() {
        val pool = Executors.newFixedThreadPool(10)
        val started = CountDownLatch(1)
        val current = AtomicInteger(0)
        val max = AtomicInteger(0)
        try {
            val jobs = (0 until 20).map { i ->
                pool.submit {
                    started.await()
                    RemoteRequestGate.run(priority = i % 3 == 0) {
                        val active = current.incrementAndGet()
                        max.updateAndGet { old -> maxOf(old, active) }
                        Thread.sleep(3)
                        current.decrementAndGet()
                    }
                }
            }
            started.countDown()
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, max.get())
            assertEquals(0, current.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun exceptionReleasesGateForNextRequest() {
        runCatching { RemoteRequestGate.run(priority = false) { throw IllegalStateException("failed") } }
        val result = RemoteRequestGate.run(priority = true) { 42 }
        assertEquals(42, result)
    }

    @Test fun higherPriorityRequestRunsBeforeWaitingThumbnail() {
        val pool = Executors.newFixedThreadPool(3)
        val active = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val first = pool.submit {
                RemoteRequestGate.run(priority = false) {
                    active.countDown()
                    release.await()
                }
            }
            assertTrue(active.await(5, TimeUnit.SECONDS))
            val thumbnail = Thread {
                RemoteRequestGate.run(priority = false) { order.add("thumb") }
            }
            val download = Thread {
                RemoteRequestGate.run(priority = true) { order.add("download") }
            }
            thumbnail.start()
            download.start()
            // Both workers must actually be waiting before releasing the lock.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((thumbnail.state != Thread.State.WAITING ||
                download.state != Thread.State.WAITING) &&
                System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(Thread.State.WAITING, thumbnail.state)
            assertEquals(Thread.State.WAITING, download.state)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            thumbnail.join(5000)
            download.join(5000)
            assertTrue(!thumbnail.isAlive && !download.isAlive)
            assertEquals("download", order.first())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun thumbnailsPauseDuringTransferAndResumeAfterward() {
        val reached = CountDownLatch(1)
        val worker = Thread {
            RemoteRequestGate.run(priority = false) { reached.countDown() }
        }
        RemoteRequestGate.setTransferActive(true)
        try {
            worker.start()
            assertTrue(!reached.await(200, TimeUnit.MILLISECONDS))
            assertEquals(200, RemoteRequestGate.run(priority = true) { 200 })
        } finally {
            RemoteRequestGate.setTransferActive(false)
        }
        try {
            assertTrue(reached.await(3, TimeUnit.SECONDS))
            worker.join(3_000)
            assertTrue(!worker.isAlive)
        } finally {
            worker.interrupt()
        }
    }

}
