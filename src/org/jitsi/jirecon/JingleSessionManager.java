/*
/*
 * Jirecon, the JItsi REcording COntainer.
 *
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jirecon;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jirecon.TaskEvent.*;
import org.jitsi.jirecon.protocol.extension.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.packet.*;

/**
 * Manage Jingle session, join MUC, build Jingle session etc.
 * 
 * @author lishunyang
 * @author Boris Grozev
 * 
 */
public class JingleSessionManager
    implements TaskManagerEvent.JireconEventListener
{
    /**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger
        .getLogger(JingleSessionManager.class.getName());
    
    /**
     * Maximum wait time in milliseconds.
     */
    private static final int MAX_WAIT_TIME = 10000;

    /**
     * The human-readable <tt>nickname</tt> which will be set in presence sent
     * to the MUC. Not to be confused with the ID within the room.
     */
    private static final String NICKNAME = "Jirecon Recorder";
    
    /**
     * The <tt>XMPPConnection</tt> is used to send/receive XMPP packets.
     */
    private XMPPConnection connection;

    /**
     * The <tt>JireconTaskEventListener</tt>, if <tt>JireconRecorder</tt> has
     * something important, it will notify them.
     */
    private final List<TaskEventListener> listeners =
        new ArrayList<TaskEventListener>();

    /**
     * The instance of a <tt>MultiUserChat</tt>. <tt>JireconSessionImpl</tt>
     * will join it as the first step.
     */
    private MultiUserChat muc;

    /**
     * Local node's full-jid which is used for creating <tt>JingleIQ</tt>.
     */
    private String localFullJid;

    /**
     * Remote node's full-jid which is used for creating <tt>JingleIQ</tt>.
     */
    private String remoteFullJid;

    /**
     * Jingle session id which is used for making <tt>JingleIq</tt>.
     */
    private String sid;

    /**
     * <tt>Endpoint</tt>s in the meeting.
     */
    private final Map<String, EndpointInfo> endpoints
        = new HashMap<String, EndpointInfo>();

    /**
     * The list of <tt>JireconSessionPacketListener</tt> which is used for
     * handling kinds of XMPP packet.
     */
    private List<JireconSessionPacketListener> packetListeners =
        new ArrayList<JireconSessionPacketListener>();
    
    private PacketListener sendingListener;
    
    private PacketListener receivingListener;
    
    /**
     * Initialize <tt>JireconSession</tt>.
     * 
     * @param connection is used for send/receive XMPP packet.
     */
    public void init(XMPPConnection connection)
    {
        /*
         * We must make sure Libjitsi has been started.
         */
        LibJitsi.start();
        this.connection = connection;

        addPacketSendingListener();
        addPacketReceivingListener();

        /*
         * Register the packet listener to handle presence packet.
         */
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet != null && packet instanceof Presence)
                        handlePresencePacket((Presence) packet);
                }
            };

        addPacketListener(packetListener);
    }
    
    /**
     * Join a Multi-User-Chat of specified MUC jid.
     * 
     * @param mucJid The specified MUC jid.
     * @param nickname The name in MUC.
     * @throws Exception if failed to join MUC.
     */
    public void connect(String mucJid, String nickname) 
        throws Exception
    {
        joinMUC(mucJid, nickname);
    }

    /**
     * Disconnect with XMPP server and terminate the Jingle session.
     * 
     * @param reason <tt>Reason</tt> type of disconnecting.
     * @param reasonText The human-read reasons.
     */
    public void disconnect(Reason reason, String reasonText)
    {
        sendByePacket(reason, reasonText);
        leaveMUC();
        connection.removePacketSendingListener(sendingListener);
        connection.removePacketListener(receivingListener);
    }

    /**
     * Join a Multi-User-Chat of specified MUC jid.
     * 
     * @param mucJid The specified MUC jid.
     * @param nickname The name in MUC.
     * @throws Exception if failed to join MUC.
     */
    private void joinMUC(String mucJid, String nickname)
        throws Exception
    {
        muc = new MultiUserChat(connection, mucJid);
        int suffix = 1;
        String finalNickname = nickname;
        while (true)
        {
            try
            {
                muc.join(finalNickname);
                break;
            }
            catch (XMPPException e)
            {
                if (409 == e.getXMPPError().getCode() && suffix < 10)
                {
                    finalNickname = nickname + "_" + suffix++;
                    continue;
                }
                throw new Exception("Could not join MUC, " + e.getMessage());
            }
        }

        logger.info("Joined MUC as " + mucJid + "/" + finalNickname);
        Packet presence = new Presence(Presence.Type.available);
        presence.setTo(mucJid);
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension(null));
        connection.sendPacket(presence);
    }

    /**
     * Leave the Multi-User-Chat
     */
    private void leaveMUC()
    {
        logger.info("leaveMUC");

        if (null != muc)
            muc.leave();
    }

    /**
     * Send Jingle session-accept packet to the remote peer.
     * 
     * @param formatAndPTs Map between <tt>MediaFormat</tt> and payload type id.
     * @param localSsrcs Local sscrs of audio and video.
     * @param transportPEs DtlsTransport packet extensions.
     * @param fingerprintPEs Fingerprint packet extensions.
     */
    public void sendAcceptPacket(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, Long> localSsrcs,
        Map<MediaType, AbstractPacketExtension> transportPEs,
        Map<MediaType, AbstractPacketExtension> fingerprintPEs)
    {
        logger.debug("sendAcceptPacket");
        
        JingleIQ acceptIq = createAcceptPacket(formatAndPTs, localSsrcs, transportPEs, fingerprintPEs);
        connection.sendPacket(acceptIq);
    }

    /**
     * Send Jingle ack packet to remote peer.
     * 
     * @param jiq is the Jingle IQ packet that we've got.
     */
    private void sendAck(JingleIQ jiq)
    {
        logger.debug("sendAck");
        
        connection.sendPacket(IQ.createResultIQ(jiq));
    }

    /**
     * Send Jingle session-terminate packet.
     * 
     * @param reason is the <tt>Reason</tt> type of the termination packet.
     * @param reasonText is the human-read text.
     */
    private void sendByePacket(Reason reason, String reasonText)
    {
        logger.debug("sendByePacket");

        connection.sendPacket(JinglePacketFactory.createSessionTerminate(
            localFullJid, remoteFullJid, sid, reason, reasonText));
    }

    /**
     * Record some session information according Jingle session-init packet.
     * It's convenient to parse local jid, remote jid, sid, and so on, though
     * this may seems weird.
     * 
     * @param initJiq is the Jingle session-init packet.
     */
    private void recordSessionInfo(JingleIQ initJiq)
    {
        localFullJid = initJiq.getTo();
        remoteFullJid = initJiq.getFrom();
        sid = initJiq.getSID();
    }

    /**
     * Wait for Jingle session-init packet after join the MUC.
     * <p>
     * <strong>Warning:</strong> This method will block for at most
     * <tt>MAX_WAIT_TIME</tt> ms to wait for session-init packet. If time out,
     * throws exception.
     * <p>
     * Once We got session-init packet, send back ack packet.
     * 
     * @return Jingle session-init packet that we get.
     * @throws Exception if the method time out.
     */
    public JingleIQ waitForInitPacket() 
        throws Exception
    {
        logger.info("waitForInitPacket");

        final List<JingleIQ> resultList = new ArrayList<JingleIQ>();
        final Object waitForInitPacketSyncRoot = new Object();

        /*
         * Register a packet listener for handling Jingle session-init packet.
         */
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    if (packet instanceof JingleIQ)
                    {
                        final JingleIQ jiq = (JingleIQ) packet;
                        if (JingleAction.SESSION_INITIATE.equals(jiq
                            .getAction()))
                        {
                            resultList.add(jiq);
                            JingleSessionManager.this.removePacketListener(this);

                            synchronized (waitForInitPacketSyncRoot)
                            {
                                waitForInitPacketSyncRoot.notify();
                            }
                        }
                    }
                }
            };

        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForInitPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForInitPacketSyncRoot.wait(MAX_WAIT_TIME);
                    break;
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            throw new Exception(
                "Could not get session-init packet, maybe the MUC has locked.");
        }

        final JingleIQ initIq = resultList.get(0);
        recordSessionInfo(initIq);
        sendAck(initIq);

        return initIq;
    }

    /**
     * Wait for ack packet.
     * <p>
     * <strong>Warning:</strong> This method will block for at most
     * <tt>MAX_WAIT_TIME</tt> ms if there isn't ack packet.
     */
    public void waitForResultPacket() 
    {
        logger.info("waitForAckPacket");

        final List<Packet> resultList = new ArrayList<Packet>();
        final Object waitForAckPacketSyncRoot = new Object();

        /*
         * Register a packet listener for handling ack packet.
         */
        JireconSessionPacketListener packetListener =
            new JireconSessionPacketListener()
            {
                @Override
                public void handlePacket(Packet packet)
                {
                    //TODO match for the ID as well...
                    if (packet instanceof IQ && IQ.Type.RESULT.equals(((IQ)packet).getType()))
                    {
                        resultList.add(packet);
                        synchronized (waitForAckPacketSyncRoot)
                        {
                            waitForAckPacketSyncRoot.notify();
                        }
                    }
                }
            };
        //wen: disabled this, no effect
        addPacketListener(packetListener);
        boolean interrupted = false;

        synchronized (waitForAckPacketSyncRoot)
        {
            while (resultList.isEmpty())
            {
                try
                {
                    waitForAckPacketSyncRoot.wait(MAX_WAIT_TIME);
                }
                catch (InterruptedException ie)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        removePacketListener(packetListener);
        if (resultList.isEmpty())
        {
            logger.warn("Couldn't receive result packet from remote peer.");
        }
    }

    /**
     * Handle the Jingle presence packet, record the partcipant's information
     * like jid, ssrc.
     * 
     * @param p is the presence packet.
     */
    private void handlePresencePacket(Presence p)
    {
        PacketExtension packetExt = p.getExtension(MediaExtension.NAMESPACE);
        final String name = "x";
        final String namespace = "http://jabber.org/protocol/muc#user";
        MUCUser userExt = (MUCUser) p.getExtension(name, namespace);
        
        /*
         * In case of presence packet isn't sent by participant, so we can't get
         * participant id from p.getFrom().
         */ 
        String participantJid = userExt.getItem().getJid();

        /*
         * Jitsi-meeting presence packet should contain participant jid and
         * media packet extension
         */
        if (null == participantJid || null == packetExt)
            return;
        
        MediaExtension mediaExt = (MediaExtension) packetExt;
        Map<MediaType, Long> ssrcs = new HashMap<MediaType, Long>();
        
        for (MediaType mediaType : new MediaType[] {MediaType.AUDIO, MediaType.VIDEO})
        {
            MediaDirection direction =
                MediaDirection.parseString(mediaExt.getDirection(mediaType
                    .toString()));
            
            if (direction.allowsSending())
            {
                ssrcs.put(mediaType,
                    Long.valueOf(mediaExt.getSsrc(mediaType.toString())));
            }
            
        }
        
        // Oh, it seems that some participant has left the MUC.
        if (p.getType() == Presence.Type.unavailable)
        {
            removeEndpoint(participantJid);
            fireEvent(new TaskEvent(
                TaskEvent.Type.PARTICIPANT_LEFT));
        }
        // Otherwise we think that some new participant has joined the MUC.
        else
        {
            if(addOrUpdateEndpoint(participantJid, ssrcs))
            {
                fireEvent(new TaskEvent(
                        TaskEvent.Type.PARTICIPANT_CAME));
            }
        }
    }
    
    /**
     * Create Jingle session-accept packet.
     * 
     * @return Jingle session-accept packet.
     */
    private JingleIQ createAcceptPacket(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, Long> localSsrcs,
        Map<MediaType, AbstractPacketExtension> transportPEs,
        Map<MediaType, AbstractPacketExtension> fingerprintPEs)
    {
        logger.debug("createSessionAcceptPacket");
        
        List<ContentPacketExtension> contentPEs =
            new ArrayList<ContentPacketExtension>();

        for (MediaType mediaType : MediaType.values())
        {
            if (!transportPEs.containsKey(mediaType) ||
                !fingerprintPEs.containsKey(mediaType))
                continue;
            
            /* The packet extension that we will create :) */
            RtpDescriptionPacketExtension descriptionPE = null;
            AbstractPacketExtension transportPE = null;
            AbstractPacketExtension fingerprintPE = null;
            ContentPacketExtension contentPE = null;
            SctpMapExtension sctpMapPE = null;

            /*
             * 1. Create DescriptionPE. Only audio and video need this one.
             */
            if (MediaType.AUDIO == mediaType || MediaType.VIDEO == mediaType) 
            {
                descriptionPE =
                    createDescriptionPacketExt(mediaType,
                        formatAndPTs.get(mediaType), localSsrcs.get(mediaType));
            }
            
            /* 
             * 2. Create TransportPE with FingerprintPE. 
             */
            transportPE = transportPEs.get(mediaType);
            fingerprintPE =
                fingerprintPEs.get(mediaType);
            transportPE.addChildExtension(fingerprintPE);
            
            /* 
             * 3. Create sctpMapPE. Only data need this one. 
             */
            if (MediaType.DATA == mediaType)
            {
                /*
                 * Actually the port could be any number, but let's keep it 5000
                 * everywhere.
                 */
                final int port = 5000;
                /*
                 * Jirecon didn't care about this at this moment. So just set it 1024. 
                 */
                final int numStreams = 1024;
                
                sctpMapPE = new SctpMapExtension();
                sctpMapPE.setPort(port);
                sctpMapPE.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                sctpMapPE.setStreams(numStreams);
                transportPE.addChildExtension(sctpMapPE);
            }

            /*
             * 4. Create Content packet extension with DescriptionPE(it could be
             * null) and TransportPE above.
             */
            contentPE =
                createContentPacketExtension(mediaType.toString(),
                    descriptionPE, transportPE);

            contentPEs.add(contentPE);
        }

        JingleIQ acceptJiq =
            JinglePacketFactory.createSessionAccept(localFullJid,
                remoteFullJid, sid, contentPEs);
        acceptJiq.setInitiator(remoteFullJid);

        return acceptJiq;
    }
    
    /**
     * Create content packet extension in Jingle session-accept packet.
     * 
     * @return content packet extension.
     */
    private ContentPacketExtension createContentPacketExtension(
        String name,
        RtpDescriptionPacketExtension descriptionPE,
        AbstractPacketExtension transportPE)
    {
        logger.debug(this.getClass() + " createContentPacketExtension");
        
        ContentPacketExtension content = new ContentPacketExtension();
        content.setCreator(CreatorEnum.responder);
        content.setName(name);
        content.setSenders(SendersEnum.initiator);
        if (null != descriptionPE)
        {
            content.addChildExtension(descriptionPE);
        }
        content.addChildExtension(transportPE);

        return content;
    }

    /**
     * Create <tt>RtpDescriptionPacketExtension</tt> with specified mediatype,
     * media formats, payload type ids and ssrcs.
     * 
     * @param mediaType
     * @param formatAndPayloadTypes
     * @param localSsrc
     * @return
     */
    private RtpDescriptionPacketExtension createDescriptionPacketExt(
        MediaType mediaType, Map<MediaFormat, Byte> formatAndPayloadTypes,
        Long localSsrc)
    {
        RtpDescriptionPacketExtension description =
            new RtpDescriptionPacketExtension();
        
        /*
         *  1. Set media type.
         */
        description.setMedia(mediaType.toString());
        /*
         *  2. Set local ssrc.
         */
        description.setSsrc(localSsrc.toString());

        /*
         *  3. Set payload type id.
         */
        for (Map.Entry<MediaFormat, Byte> e : formatAndPayloadTypes.entrySet())
        {
            PayloadTypePacketExtension payloadType =
                new PayloadTypePacketExtension();
            payloadType.setId(e.getValue());
            payloadType.setName(e.getKey().getEncoding());
            if (e.getKey() instanceof AudioMediaFormat)
            {
                payloadType.setChannels(((AudioMediaFormat) e.getKey())
                    .getChannels());
            }
            payloadType.setClockrate((int) e.getKey().getClockRate());
            for (Map.Entry<String, String> en : e.getKey()
                .getFormatParameters().entrySet())
            {
                ParameterPacketExtension parameter =
                    new ParameterPacketExtension();
                parameter.setName(en.getKey());
                parameter.setValue(en.getValue());
                payloadType.addParameter(parameter);
            }
            description.addPayloadType(payloadType);
        }

        final MediaService mediaService = LibJitsi.getMediaService();
        
        /*
         *  4. Set source information.
         */
        SourcePacketExtension sourcePacketExtension =
            new SourcePacketExtension();
        final String label = UUID.randomUUID().toString().replace("-", "");
        final String msLabel = UUID.randomUUID().toString();
        
        sourcePacketExtension.setSSRC(localSsrc);
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "cname", mediaService.getRtpCname()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "msid", msLabel + " " + label));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "mslabel", msLabel));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension(
            "label", label));
        description.addChildExtension(sourcePacketExtension);
        
        return description;
    }
    
    public void addTaskEventListener(TaskEventListener listener)
    {
        synchronized (listeners)
        {
            listeners.add(listener);
        }
    }

    public void removeTaskEventListener(TaskEventListener listener)
    {
        synchronized (listeners)
        {
            listeners.remove(listener);
        }
    }

    /**
     * Fire a <tt>TaskEvent</tt>, notify listeners we've made new
     * progress which they may interest in.
     * 
     * @param event
     */
    private void fireEvent(TaskEvent event)
    {
        synchronized (listeners)
        {
            for (TaskEventListener l : listeners)
                l.handleTaskEvent(event);
        }
    }

    /**
     * Send the XMPP packet to the packet listeners to handle.
     * 
     * @param packet is the packet that we've gotten.
     */
    private void handlePacket(Packet packet)
    {
        for (JireconSessionPacketListener l : packetListeners)
        {
            l.handlePacket(packet);
        }
    }

    /**
     * Add packet sending listener to connection. This method is used just for
     * debugging.
     */
    private void addPacketSendingListener()
    {
        sendingListener = new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                logger.debug("--->: " + packet.toXML());
            }
        };

        connection.addPacketSendingListener(sendingListener, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                return true;
            }
        });
    }

    /**
     * Add packet receiving listener to connection.
     * <p>
     * <strong>Warning:</strong> Packet will be ignored if its destination jid
     * is not equal with local jid.
     */
    private void addPacketReceivingListener()
    {
        receivingListener = new PacketListener()
        {
            @Override
            public void processPacket(Packet packet)
            {
                logger.debug(packet.getClass() + "<---: " + packet.toXML());
                handlePacket(packet);
            }
        };

        connection.addPacketListener(receivingListener, new PacketFilter()
        {
            @Override
            public boolean accept(Packet packet)
            {
                if (null != localFullJid
                        && !packet.getTo().equals(localFullJid))
                {
                    logger.warn("packet rejected: \"to\" is " + packet.getTo()
                                        + ", but we are " + localFullJid);
                    return false;
                }
                return true;
            }
        });
    }

    /**
     * Handles events coming from the {@link org.jitsi.jirecon.Task} which owns
     * us.
     *
     * @param evt is the specified event.
     */
    @Override
    public void handleEvent(TaskManagerEvent evt)
    {
        TaskManagerEvent.Type type = evt.getType();
        if (TaskManagerEvent.Type.TASK_STARTED.equals(type))
        {
            sendRecordingOnPresence();
        }
        else if (TaskManagerEvent.Type.TASK_ABORTED.equals(type)
            || TaskManagerEvent.Type.TASK_FINISED.equals(type))
        {
            sendRecordingOffPresence();
        }
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned on.
     */
    private void sendRecordingOnPresence()
    {
        Packet presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension("true"));
        connection.sendPacket(presence);
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned off.
     */
    private void sendRecordingOffPresence()
    {
        Packet presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension("false"));
        connection.sendPacket(presence);
    }

    /**
     * The packet listener interface in Observer pattern. Anyone who wants to
     * handle packet need to implement it.
     * 
     * @author lishunyang
     * 
     */
    private interface JireconSessionPacketListener
    {
        /**
         * Handle packet.
         * 
         * @param packet is the packet that we've gotten.
         */
        public void handlePacket(Packet packet);
    }

    /**
     * Register a packet listener to this <tt>JireconSessionImpl</tt>.
     * 
     * @param listener is the one that you want to add.
     */
    private void addPacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.add(listener);
    }

    /**
     * Remove a packet listener from this <tt>JireconSessionImpl</tt>.
     * 
     * @param listener is the one that you want to remove.
     */
    private void removePacketListener(JireconSessionPacketListener listener)
    {
        packetListeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    public List<EndpointInfo> getEndpoints()
    {
        synchronized (endpoints)
        {
            return new LinkedList<EndpointInfo>(endpoints.values());
        }
    }

    /**
     * Add a new endpoint to {@link #endpoints}, or update the stored
     * information for the endpoint if it is already in the list.
     *
     * @param jid The endpoint id.
     * @param ssrcs The SSRCs of the endpoint, according to media type.
     *
     * @return <tt>true</tt> if the endpoint was added to the list, and
     * <tt>false</tt> otherwise.
     */
    private boolean addOrUpdateEndpoint(String jid, Map<MediaType, Long> ssrcs)
    {
        synchronized (endpoints)
        {
            boolean added = false;
            EndpointInfo endpoint = endpoints.get(jid);
            if (endpoint == null)
            {
                endpoint = new EndpointInfo();
                added = true;
            }
            
            endpoint.setId(jid);
            for (MediaType mediaType : new MediaType[]
            { MediaType.AUDIO, MediaType.VIDEO })
            {
                endpoint.setSsrc(mediaType, ssrcs.get(mediaType));
            }

            endpoints.put(jid, endpoint);
            return added;
        }
    }

    /**
     * Remove an endpoint with the given JID specified endpoint.
     * 
     * @param jid Indicate which endpoint to remove.
     */
    private void removeEndpoint(String jid)
    {
        logger.debug("Remove Endpoint " + jid);
        
        synchronized (endpoints)
        {
            endpoints.remove(jid);
        }
    }
}
