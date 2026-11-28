import com.github.manevolent.ts3j.enums.CodecType;
import com.github.manevolent.ts3j.protocol.NetworkPacket;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader;
import com.github.manevolent.ts3j.protocol.header.HeaderFlag;
import com.github.manevolent.ts3j.protocol.header.ServerPacketHeader;
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice;
import com.github.manevolent.ts3j.protocol.packet.PacketBodyType;
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

final class VoiceUdpBoundaryProbe extends LocalTeamspeakClientSocket {
    public static void main(String[] arguments) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket receiver = new DatagramSocket(0, loopback);
             DatagramSocket peer = new DatagramSocket(0, loopback)) {
            peer.setSoTimeout(1000);
            VoiceUdpBoundaryProbe client = new VoiceUdpBoundaryProbe();
            setField(LocalTeamspeakClientSocket.class, client, "socket", receiver);
            setField(LocalTeamspeakClientSocket.class, client, "remote", peer.getLocalSocketAddress());
            setField(AbstractTeamspeakClientSocket.class, client, "connectionState", ClientConnectionState.CONNECTING);

            long incomingStart = System.nanoTime();
            sendVoiceFromPeer(peer, (InetSocketAddress) receiver.getLocalSocketAddress());
            PacketBody0Voice body = (PacketBody0Voice) client.readPacket().getBody();
            requireInBounds(incomingStart, body.getUdpReceivedNanoTime());
            if (body.getCodecData().length != 1) throw new AssertionError("Synthetic voice body changed");

            NetworkPacket voice = outbound(PacketBodyType.VOICE);
            long outgoingStart = System.nanoTime();
            client.writeNetworkPacket(voice);
            requireInBounds(outgoingStart, voice.getSentNanoTime());
            receiveAtPeer(peer);

            NetworkPacket control = outbound(PacketBodyType.PING);
            client.writeNetworkPacket(control);
            receiveAtPeer(peer);
            if (control.getSentNanoTime() != null) throw new AssertionError("Non-voice send was stamped");

            receiver.close();
            NetworkPacket failed = outbound(PacketBodyType.VOICE);
            try {
                client.writeNetworkPacket(failed);
                throw new AssertionError("Closed socket unexpectedly sent");
            } catch (IOException expected) {
                if (failed.getSentNanoTime() != null) throw new AssertionError("Failed datagram was stamped");
            }
        }
        System.out.println("TS3J_VOICE_UDP_LOOPBACK_OK");
    }

    @Override
    protected boolean isReading() {
        return true;
    }

    private static void sendVoiceFromPeer(DatagramSocket peer, InetSocketAddress destination) throws IOException {
        ServerPacketHeader header = new ServerPacketHeader();
        header.setType(PacketBodyType.VOICE);
        header.setPacketFlag(HeaderFlag.UNENCRYPTED, true);
        PacketBody0Voice body = new PacketBody0Voice(ProtocolRole.SERVER);
        body.setCodecType(CodecType.OPUS_VOICE);
        body.setCodecData(new byte[1]);
        ByteBuffer wire = ByteBuffer.allocate(header.getSize() + body.getSize());
        header.write(wire);
        body.write(wire);
        peer.send(new DatagramPacket(wire.array(), wire.position(), destination));
    }

    private static NetworkPacket outbound(PacketBodyType type) {
        ClientPacketHeader header = new ClientPacketHeader();
        header.setType(type);
        byte[] fixture = new byte[1];
        return new NetworkPacket(new DatagramPacket(fixture, fixture.length), header, ByteBuffer.wrap(fixture));
    }

    private static void receiveAtPeer(DatagramSocket peer) throws IOException {
        byte[] fixture = new byte[16];
        peer.receive(new DatagramPacket(fixture, fixture.length));
    }

    private static void requireInBounds(long calledAt, Long observed) {
        if (observed == null || observed - calledAt < 0 || System.nanoTime() - observed < 0) {
            throw new AssertionError("UDP timestamp missing or not on the expected monotonic clock");
        }
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
