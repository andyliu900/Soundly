package com.ideacode.soundly_sdk.internal

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Test

class SoundlyDispatchersTest {

    @Test
    fun acceptsDeterministicDispatcherOverrides() {
        val dispatchers = SoundlyDispatchers(
            io = Dispatchers.Unconfined,
            computation = Dispatchers.Unconfined
        )

        assertSame(Dispatchers.Unconfined, dispatchers.io)
        assertSame(Dispatchers.Unconfined, dispatchers.computation)
    }
}
