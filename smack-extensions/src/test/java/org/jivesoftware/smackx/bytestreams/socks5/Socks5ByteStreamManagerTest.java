/**
 *
 * Copyright the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.bytestreams.socks5;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.concurrent.TimeoutException;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.FeatureNotSupportedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.ErrorIQ;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.StanzaError;
import org.jivesoftware.smack.util.NetworkUtil;

import org.jivesoftware.smackx.bytestreams.socks5.packet.Bytestream;
import org.jivesoftware.smackx.bytestreams.socks5.packet.Bytestream.StreamHost;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo.Identity;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.disco.packet.DiscoverItems.Item;

import org.jivesoftware.util.ConnectionUtils;
import org.jivesoftware.util.Protocol;
import org.jivesoftware.util.Verification;
import org.junit.Test;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.JidTestUtil;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

/**
 * Test for Socks5BytestreamManager.
 *
 * @author Henning Staib
 */
public class Socks5ByteStreamManagerTest {

    // settings
    private static final EntityFullJid initiatorJID = JidTestUtil.DUMMY_AT_EXAMPLE_ORG_SLASH_DUMMYRESOURCE;
    private static final EntityFullJid targetJID = JidTestUtil.FULL_JID_1_RESOURCE_1;
    private static final DomainBareJid xmppServer = initiatorJID.asDomainBareJid();
    private static final DomainBareJid proxyJID = JidTestUtil.MUC_EXAMPLE_ORG;
    private static final String proxyAddress = "127.0.0.1";

    /**
     * Test that {@link Socks5BytestreamManager#getBytestreamManager(XMPPConnection)} returns one
     * bytestream manager for every connection.
     */
    @Test
    public void shouldHaveOneManagerForEveryConnection() {
        // mock two connections
        XMPPConnection connection1 = mock(XMPPConnection.class);
        XMPPConnection connection2 = mock(XMPPConnection.class);

        /*
         * create service discovery managers for the connections because the
         * ConnectionCreationListener is not called when creating mocked connections
         */
        ServiceDiscoveryManager.getInstanceFor(connection1);
        ServiceDiscoveryManager.getInstanceFor(connection2);

        // get bytestream manager for the first connection twice
        Socks5BytestreamManager conn1ByteStreamManager1 = Socks5BytestreamManager.getBytestreamManager(connection1);
        Socks5BytestreamManager conn1ByteStreamManager2 = Socks5BytestreamManager.getBytestreamManager(connection1);

        // get bytestream manager for second connection
        Socks5BytestreamManager conn2ByteStreamManager1 = Socks5BytestreamManager.getBytestreamManager(connection2);

        // assertions
        assertEquals(conn1ByteStreamManager1, conn1ByteStreamManager2);
        assertNotSame(conn1ByteStreamManager1, conn2ByteStreamManager1);
    }

    /**
     * The SOCKS5 Bytestream feature should be removed form the service discovery manager if Socks5
     * bytestream feature is disabled.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPErrorException
     */
    @Test
    public void shouldDisableService() throws XMPPErrorException, SmackException, InterruptedException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);

        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        ServiceDiscoveryManager discoveryManager = ServiceDiscoveryManager.getInstanceFor(connection);

        assertTrue(discoveryManager.includesFeature(Bytestream.NAMESPACE));

        byteStreamManager.disableService();

        assertFalse(discoveryManager.includesFeature(Bytestream.NAMESPACE));
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid)} should throw an exception
     * if the given target does not support SOCKS5 Bytestream.
     * @throws XMPPException
     * @throws InterruptedException
     * @throws SmackException
     * @throws IOException
     */
    @Test
    public void shouldFailIfTargetDoesNotSupportSocks5()
                    throws XMPPException, SmackException, InterruptedException, IOException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);

        try {
            // build empty discover info as reply if targets features are queried
            DiscoverInfo discoverInfo = new DiscoverInfo();
            protocol.addResponse(discoverInfo);

            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID);

            fail("exception should be thrown");
        }
        catch (FeatureNotSupportedException e) {
            assertTrue(e.getFeature().equals("SOCKS5 Bytestream"));
            assertTrue(e.getJid().equals(targetJID));
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if XMPP
     * server doesn't return any proxies.
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldFailIfNoSocks5ProxyFound1()
                    throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldFailIfNoSocks5ProxyFound1";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items with no proxy items
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);

        // return the item with no proxy if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (SmackException e) {
            protocol.verifyAll();
            assertTrue(e.getMessage().contains("no SOCKS5 proxies available"));
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if no
     * proxy is a SOCKS5 proxy.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldFailIfNoSocks5ProxyFound2()
                    throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldFailIfNoSocks5ProxyFound2";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about NOT being a Socks5
        // proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("noproxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (SmackException e) {
            protocol.verifyAll();
            assertTrue(e.getMessage().contains("no SOCKS5 proxies available"));
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if no
     * SOCKS5 proxy can be found. If it turns out that a proxy is not a SOCKS5 proxy it should not
     * be queried again.
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldBlacklistNonSocks5Proxies() throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldBlacklistNonSocks5Proxies";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about NOT being a Socks5
        // proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("noproxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (SmackException e) {
            protocol.verifyAll();
            assertTrue(e.getMessage().contains("no SOCKS5 proxies available"));
        }

        /* retry to establish SOCKS5 Bytestream */

        // add responses for service discovery again
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (SmackException e) {
            /*
             * #verifyAll() tests if the number of requests and responses corresponds and should
             * fail if the invalid proxy is queried again
             */
            protocol.verifyAll();
            assertTrue(e.getMessage().contains("no SOCKS5 proxies available"));
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if the
     * target does not accept a SOCKS5 Bytestream. See <a
     * href="http://xmpp.org/extensions/xep-0065.html#usecase-alternate">XEP-0065 Section 5.2 A2</a>
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldFailIfTargetDoesNotAcceptSocks5Bytestream() throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldFailIfTargetDoesNotAcceptSocks5Bytestream";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about being a SOCKS5 proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("proxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the socks5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build a socks5 stream host info containing the address and the port of the
        // proxy
        Bytestream streamHostInfo = Socks5PacketUtils.createBytestreamResponse(proxyJID,
                        initiatorJID);
        streamHostInfo.addStreamHost(proxyJID, proxyAddress, 7778);

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build error packet to reject SOCKS5 Bytestream
        StanzaError.Builder builder = StanzaError.getBuilder(StanzaError.Condition.not_acceptable);
        IQ rejectPacket = new ErrorIQ(builder);
        rejectPacket.setFrom(targetJID);
        rejectPacket.setTo(initiatorJID);

        // return error packet as response to the bytestream initiation
        protocol.addResponse(rejectPacket, Verification.correspondingSenderReceiver,
                        Verification.requestTypeSET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (XMPPErrorException e) {
            protocol.verifyAll();
            assertEquals(rejectPacket.getError(), e.getStanzaError());
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if the
     * proxy used by target is invalid.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldFailIfTargetUsesInvalidSocks5Proxy()
                    throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldFailIfTargetUsesInvalidSocks5Proxy";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        // TODO: It appears that it is not required to disable the local stream host for this unit test.
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about being a SOCKS5 proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("proxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the socks5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build a socks5 stream host info containing the address and the port of the
        // proxy
        Bytestream streamHostInfo = Socks5PacketUtils.createBytestreamResponse(proxyJID,
                        initiatorJID);
        streamHostInfo.addStreamHost(proxyJID, proxyAddress, 7778);

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build used stream host response with unknown proxy
        Bytestream streamHostUsedPacket = Socks5PacketUtils.createBytestreamResponse(targetJID,
                        initiatorJID);
        streamHostUsedPacket.setSessionID(sessionID);
        streamHostUsedPacket.setUsedHost(JidCreate.from("invalid.proxy"));

        // return used stream host info as response to the bytestream initiation
        protocol.addResponse(streamHostUsedPacket, Verification.correspondingSenderReceiver,
                        Verification.requestTypeSET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (SmackException e) {
            protocol.verifyAll();
            assertTrue(e.getMessage().contains("Remote user responded with unknown host"));
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should fail if
     * initiator can not connect to the SOCKS5 proxy used by target.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     */
    @Test
    public void shouldFailIfInitiatorCannotConnectToSocks5Proxy()
                    throws SmackException, InterruptedException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldFailIfInitiatorCannotConnectToSocks5Proxy";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about being a SOCKS5 proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("proxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the socks5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build a socks5 stream host info containing the address and the port of the
        // proxy
        Bytestream streamHostInfo = Socks5PacketUtils.createBytestreamResponse(proxyJID,
                        initiatorJID);
        streamHostInfo.addStreamHost(proxyJID, proxyAddress, 7778);

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build used stream host response
        Bytestream streamHostUsedPacket = Socks5PacketUtils.createBytestreamResponse(targetJID,
                        initiatorJID);
        streamHostUsedPacket.setSessionID(sessionID);
        streamHostUsedPacket.setUsedHost(proxyJID);

        // return used stream host info as response to the bytestream initiation
        protocol.addResponse(streamHostUsedPacket, new Verification<Bytestream, Bytestream>() {

            @Override
            public void verify(Bytestream request, Bytestream response) {
                // verify SOCKS5 Bytestream request
                assertEquals(response.getSessionID(), request.getSessionID());
                assertEquals(1, request.getStreamHosts().size());
                StreamHost streamHost = (StreamHost) request.getStreamHosts().toArray()[0];
                assertEquals(response.getUsedHost().getJID(), streamHost.getJID());
            }

        }, Verification.correspondingSenderReceiver, Verification.requestTypeSET);

        try {
            // start SOCKS5 Bytestream
            byteStreamManager.establishSession(targetJID, sessionID);

            fail("exception should be thrown");
        }
        catch (IOException e) {
            // initiator can't connect to proxy because it is not running
            protocol.verifyAll();
            Throwable actualCause = e.getCause().getCause();
            assertEquals(ConnectException.class, actualCause.getClass());
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} should successfully
     * negotiate and return a SOCKS5 Bytestream connection.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     */
    @Test
    public void shouldNegotiateSocks5BytestreamAndTransferData()
                    throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldNegotiateSocks5BytestreamAndTransferData";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        /**
         * create responses in the order they should be queried specified by the XEP-0065
         * specification
         */

        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        Item item = new Item(proxyJID);
        discoverItems.addItem(item);

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about being a SOCKS5 proxy
        DiscoverInfo proxyInfo = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity = new Identity("proxy", proxyJID.toString(), "bytestreams");
        proxyInfo.addIdentity(identity);

        // return the socks5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build a socks5 stream host info containing the address and the port of the
        // proxy
        ServerSocket proxyServerSocket = NetworkUtil.getSocketOnLoopback();
        Bytestream streamHostInfo = Socks5PacketUtils.createBytestreamResponse(proxyJID,
                        initiatorJID);
        streamHostInfo.addStreamHost(proxyJID, proxyAddress, proxyServerSocket.getLocalPort());

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build used stream host response
        Bytestream streamHostUsedPacket = Socks5PacketUtils.createBytestreamResponse(targetJID,
                        initiatorJID);
        streamHostUsedPacket.setSessionID(sessionID);
        streamHostUsedPacket.setUsedHost(proxyJID);

        // return used stream host info as response to the bytestream initiation
        protocol.addResponse(streamHostUsedPacket, new Verification<Bytestream, Bytestream>() {

            @Override
            public void verify(Bytestream request, Bytestream response) {
                assertEquals(response.getSessionID(), request.getSessionID());
                assertEquals(1, request.getStreamHosts().size());
                StreamHost streamHost = (StreamHost) request.getStreamHosts().toArray()[0];
                assertEquals(response.getUsedHost().getJID(), streamHost.getJID());
            }

        }, Verification.correspondingSenderReceiver, Verification.requestTypeSET);

        // build response to proxy activation
        IQ activationResponse = Socks5PacketUtils.createActivationConfirmation(proxyJID,
                        initiatorJID);

        // return proxy activation response if proxy should be activated
        protocol.addResponse(activationResponse, new Verification<Bytestream, IQ>() {

            @Override
            public void verify(Bytestream request, IQ response) {
                assertEquals(targetJID, request.getToActivate().getTarget());
            }

        }, Verification.correspondingSenderReceiver, Verification.requestTypeSET);

        // start a local SOCKS5 proxy
        try (Socks5TestProxy socks5Proxy = new Socks5TestProxy(proxyServerSocket)) {

            // create digest to get the socket opened by target
            String digest = Socks5Utils.createDigest(sessionID, initiatorJID, targetJID);

            // finally call the method that should be tested
            OutputStream outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            InputStream inputStream = socks5Proxy.getSocket(digest).getInputStream();

            byte[] data = new byte[] { 1, 2, 3 };
            outputStream.write(data);

            byte[] result = new byte[3];
            inputStream.read(result);

            assertArrayEquals(data, result);
        }

        protocol.verifyAll();
    }

    /**
     * If multiple network addresses are added to the local SOCKS5 proxy, all of them should be
     * contained in the SOCKS5 Bytestream request.
     *
     * @throws InterruptedException
     * @throws SmackException
     * @throws IOException
     * @throws XMPPException
     * @throws TimeoutException
     */
    @Test
    public void shouldUseMultipleAddressesForLocalSocks5Proxy()
                    throws SmackException, InterruptedException, IOException, TimeoutException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldUseMultipleAddressesForLocalSocks5Proxy";

        // start a local SOCKS5 proxy
        Socks5Proxy socks5Proxy = new Socks5Proxy();
        socks5Proxy.start();
        try {
            assertTrue(socks5Proxy.isRunning());

            // get Socks5ByteStreamManager for connection
            Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);

            /**
             * create responses in the order they should be queried specified by the XEP-0065
             * specification
             */

            // build discover info that supports the SOCKS5 feature
            DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
            discoverInfo.addFeature(Bytestream.NAMESPACE);

            // return that SOCKS5 is supported if target is queried
            protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                            Verification.requestTypeGET);

            // build discover items containing no proxy item
            DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                            initiatorJID);

            // return the discover item if XMPP server is queried
            protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                            Verification.requestTypeGET);

            // build used stream host response
            Bytestream streamHostUsedPacket = Socks5PacketUtils.createBytestreamResponse(targetJID,
                            initiatorJID);
            streamHostUsedPacket.setSessionID(sessionID);
            streamHostUsedPacket.setUsedHost(initiatorJID); // local proxy used

            // return used stream host info as response to the bytestream initiation
            protocol.addResponse(streamHostUsedPacket, new Verification<Bytestream, Bytestream>() {
                @Override
                public void verify(Bytestream request, Bytestream response) {
                    assertEquals(response.getSessionID(), request.getSessionID());
                    StreamHost streamHost1 = request.getStreamHosts().get(0);
                    assertEquals(response.getUsedHost().getJID(), streamHost1.getJID());
                    StreamHost streamHost2 = request.getStreamHosts().get(request.getStreamHosts().size() - 1);
                    assertEquals(response.getUsedHost().getJID(), streamHost2.getJID());
                    assertEquals("localAddress", streamHost2.getAddress());
                }
            }, Verification.correspondingSenderReceiver, Verification.requestTypeSET);

            // create digest to get the socket opened by target
            String digest = Socks5Utils.createDigest(sessionID, initiatorJID, targetJID);

            // connect to proxy as target
            socks5Proxy.addTransfer(digest);
            StreamHost streamHost = new StreamHost(targetJID,
                            socks5Proxy.getLocalAddresses().get(0),
                            socks5Proxy.getPort());
            Socks5Client socks5Client = new Socks5Client(streamHost, digest);
            InputStream inputStream = socks5Client.getSocket(2000).getInputStream();

            // add another network address before establishing SOCKS5 Bytestream
            socks5Proxy.addLocalAddress("localAddress");

            // finally call the method that should be tested
            OutputStream outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            byte[] data = new byte[] { 1, 2, 3 };
            outputStream.write(data);

            byte[] result = new byte[3];
            inputStream.read(result);

            assertArrayEquals(data, result);

            protocol.verifyAll();
            } finally {
                socks5Proxy.stop();
            }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} the first time
     * should successfully negotiate a SOCKS5 Bytestream via the second SOCKS5 proxy and should
     * prioritize this proxy for a second SOCKS5 Bytestream negotiation.
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     * @throws IOException
     *
     */
    @Test
    public void shouldPrioritizeSecondSocks5ProxyOnSecondAttempt() throws SmackException, InterruptedException, IOException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldPrioritizeSecondSocks5ProxyOnSecondAttempt";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        assertTrue(byteStreamManager.isProxyPrioritizationEnabled());

        Verification<Bytestream, Bytestream> streamHostUsedVerification1 = new Verification<Bytestream, Bytestream>() {

            @Override
            public void verify(Bytestream request, Bytestream response) {
                assertEquals(response.getSessionID(), request.getSessionID());
                assertEquals(2, request.getStreamHosts().size());
                // verify that the used stream host is the second in list
                StreamHost streamHost = (StreamHost) request.getStreamHosts().toArray()[1];
                assertEquals(response.getUsedHost().getJID(), streamHost.getJID());
            }

        };

        // start a local SOCKS5 proxy
        try (Socks5TestProxy socks5Proxy = new Socks5TestProxy()) {
            createResponses(protocol, sessionID, streamHostUsedVerification1, socks5Proxy);

            // create digest to get the socket opened by target
            String digest = Socks5Utils.createDigest(sessionID, initiatorJID, targetJID);

            // call the method that should be tested
            OutputStream outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            InputStream inputStream = socks5Proxy.getSocket(digest).getInputStream();

            byte[] data = new byte[] { 1, 2, 3 };
            outputStream.write(data);

            byte[] result = new byte[3];
            inputStream.read(result);

            assertArrayEquals(data, result);

            protocol.verifyAll();

            Verification<Bytestream, Bytestream> streamHostUsedVerification2 = new Verification<Bytestream, Bytestream>() {

                @Override
                public void verify(Bytestream request, Bytestream response) {
                    assertEquals(response.getSessionID(), request.getSessionID());
                    assertEquals(2, request.getStreamHosts().size());
                    // verify that the used stream host is the first in list
                    StreamHost streamHost = (StreamHost) request.getStreamHosts().toArray()[0];
                    assertEquals(response.getUsedHost().getJID(), streamHost.getJID());
                }

            };
            createResponses(protocol, sessionID, streamHostUsedVerification2, socks5Proxy);

            // call the method that should be tested again
            outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            inputStream = socks5Proxy.getSocket(digest).getInputStream();

            outputStream.write(data);

            inputStream.read(result);

            assertArrayEquals(data, result);

            protocol.verifyAll();
        }
    }

    /**
     * Invoking {@link Socks5BytestreamManager#establishSession(org.jxmpp.jid.Jid, String)} the first time
     * should successfully negotiate a SOCKS5 Bytestream via the second SOCKS5 proxy. The second
     * negotiation should run in the same manner if prioritization is disabled.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws SmackException
     * @throws XMPPException
     *
     */
    @Test
    public void shouldNotPrioritizeSocks5ProxyIfPrioritizationDisabled() throws IOException, SmackException, InterruptedException, XMPPException {
        final Protocol protocol = new Protocol();
        final XMPPConnection connection = ConnectionUtils.createMockedConnection(protocol, initiatorJID);
        final String sessionID = "session_id_shouldNotPrioritizeSocks5ProxyIfPrioritizationDisabled";

        // get Socks5ByteStreamManager for connection
        Socks5BytestreamManager byteStreamManager = Socks5BytestreamManager.getBytestreamManager(connection);
        byteStreamManager.setAnnounceLocalStreamHost(false);

        byteStreamManager.setProxyPrioritizationEnabled(false);
        assertFalse(byteStreamManager.isProxyPrioritizationEnabled());

        Verification<Bytestream, Bytestream> streamHostUsedVerification = new Verification<Bytestream, Bytestream>() {

            @Override
            public void verify(Bytestream request, Bytestream response) {
                assertEquals(response.getSessionID(), request.getSessionID());
                assertEquals(2, request.getStreamHosts().size());
                // verify that the used stream host is the second in list
                StreamHost streamHost = (StreamHost) request.getStreamHosts().toArray()[1];
                assertEquals(response.getUsedHost().getJID(), streamHost.getJID());
            }

        };

        // start a local SOCKS5 proxy
        try (Socks5TestProxy socks5Proxy = new Socks5TestProxy()) {
            createResponses(protocol, sessionID, streamHostUsedVerification, socks5Proxy);

            // create digest to get the socket opened by target
            String digest = Socks5Utils.createDigest(sessionID, initiatorJID, targetJID);

            // call the method that should be tested
            OutputStream outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            InputStream inputStream = socks5Proxy.getSocket(digest).getInputStream();

            byte[] data = new byte[] { 1, 2, 3 };
            outputStream.write(data);

            byte[] result = new byte[3];
            inputStream.read(result);

            assertArrayEquals(data, result);

            protocol.verifyAll();

            createResponses(protocol, sessionID, streamHostUsedVerification, socks5Proxy);

            // call the method that should be tested again
            outputStream = byteStreamManager.establishSession(targetJID, sessionID).getOutputStream();

            // test the established bytestream
            inputStream = socks5Proxy.getSocket(digest).getInputStream();

            outputStream.write(data);

            inputStream.read(result);

            assertArrayEquals(data, result);
        }

        protocol.verifyAll();
    }

    private static void createResponses(Protocol protocol, String sessionID,
                    Verification<Bytestream, Bytestream> streamHostUsedVerification, Socks5TestProxy socks5TestProxy)
                    throws XmppStringprepException {
        // build discover info that supports the SOCKS5 feature
        DiscoverInfo discoverInfo = Socks5PacketUtils.createDiscoverInfo(targetJID, initiatorJID);
        discoverInfo.addFeature(Bytestream.NAMESPACE);

        // return that SOCKS5 is supported if target is queried
        protocol.addResponse(discoverInfo, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover items containing a proxy item
        DiscoverItems discoverItems = Socks5PacketUtils.createDiscoverItems(xmppServer,
                        initiatorJID);
        discoverItems.addItem(new Item(JidCreate.from("proxy2.xmpp-server")));
        discoverItems.addItem(new Item(proxyJID));

        // return the proxy item if XMPP server is queried
        protocol.addResponse(discoverItems, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        /*
         * build discover info for proxy "proxy2.xmpp-server" containing information about being a
         * SOCKS5 proxy
         */
        DiscoverInfo proxyInfo1 = Socks5PacketUtils.createDiscoverInfo(JidCreate.from("proxy2.xmpp-server"),
                        initiatorJID);
        Identity identity1 = new Identity("proxy", "proxy2.xmpp-server", "bytestreams");
        proxyInfo1.addIdentity(identity1);

        // return the SOCKS5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo1, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build discover info for proxy containing information about being a SOCKS5 proxy
        DiscoverInfo proxyInfo2 = Socks5PacketUtils.createDiscoverInfo(proxyJID, initiatorJID);
        Identity identity2 = new Identity("proxy", proxyJID.toString(), "bytestreams");
        proxyInfo2.addIdentity(identity2);

        // return the SOCKS5 bytestream proxy identity if proxy is queried
        protocol.addResponse(proxyInfo2, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        /*
         * build a SOCKS5 stream host info for "proxy2.xmpp-server" containing the address and the
         * port of the proxy
         */
        Bytestream streamHostInfo1 = Socks5PacketUtils.createBytestreamResponse(
                        JidCreate.from("proxy2.xmpp-server"), initiatorJID);
        streamHostInfo1.addStreamHost(JidCreate.from("proxy2.xmpp-server"), proxyAddress, socks5TestProxy.getPort());

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo1, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build a SOCKS5 stream host info containing the address and the port of the proxy
        Bytestream streamHostInfo2 = Socks5PacketUtils.createBytestreamResponse(proxyJID,
                        initiatorJID);
        streamHostInfo2.addStreamHost(proxyJID, proxyAddress, socks5TestProxy.getPort());

        // return stream host info if it is queried
        protocol.addResponse(streamHostInfo2, Verification.correspondingSenderReceiver,
                        Verification.requestTypeGET);

        // build used stream host response
        Bytestream streamHostUsedPacket = Socks5PacketUtils.createBytestreamResponse(targetJID,
                        initiatorJID);
        streamHostUsedPacket.setSessionID(sessionID);
        streamHostUsedPacket.setUsedHost(proxyJID);

        // return used stream host info as response to the bytestream initiation
        protocol.addResponse(streamHostUsedPacket, streamHostUsedVerification,
                        Verification.correspondingSenderReceiver, Verification.requestTypeSET);

        // build response to proxy activation
        IQ activationResponse = Socks5PacketUtils.createActivationConfirmation(proxyJID,
                        initiatorJID);

        // return proxy activation response if proxy should be activated
        protocol.addResponse(activationResponse, new Verification<Bytestream, IQ>() {

            @Override
            public void verify(Bytestream request, IQ response) {
                assertEquals(targetJID, request.getToActivate().getTarget());
            }

        }, Verification.correspondingSenderReceiver, Verification.requestTypeSET);
    }

}
