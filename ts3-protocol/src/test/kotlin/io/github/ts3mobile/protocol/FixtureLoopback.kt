package io.github.ts3mobile.protocol

import java.net.InetAddress

internal val fixtureIpv4Loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
