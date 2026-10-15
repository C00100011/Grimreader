package com.vdelaar.mylibby.core

import com.vdelaar.mylibby.core.network.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 17 only lets an app reach the home network after "Nearby devices" is allowed; these addresses count as home network. */
class LocalNetworkTest {
    @Test fun homeNetworkAddressesNeedThePermission() {
        for (h in listOf("192.168.1.20", "10.0.2.2", "172.16.5.4", "169.254.1.1", "nas.local", "NAS.local.", "fd12:3456::1", "fe80::1")) assertTrue(h, LocalNetwork.isLocal(h))
    }

    @Test fun loopbackAndPublicAddressesDoNot() {
        for (h in listOf("localhost", "127.0.0.1", "8.8.8.8", "1.1.1.1")) assertFalse(h, LocalNetwork.isLocal(h))
    }
}
