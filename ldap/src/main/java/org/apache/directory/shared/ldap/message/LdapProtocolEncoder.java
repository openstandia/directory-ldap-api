/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.shared.ldap.message;


import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.directory.shared.asn1.ber.tlv.TLV;
import org.apache.directory.shared.asn1.ber.tlv.UniversalTag;
import org.apache.directory.shared.asn1.ber.tlv.Value;
import org.apache.directory.shared.asn1.codec.EncoderException;
import org.apache.directory.shared.asn1.util.Asn1StringUtils;
import org.apache.directory.shared.i18n.I18n;
import org.apache.directory.shared.ldap.codec.LdapConstants;
import org.apache.directory.shared.ldap.codec.MessageEncoderException;
import org.apache.directory.shared.ldap.codec.controls.CodecControl;
import org.apache.directory.shared.ldap.entry.BinaryValue;
import org.apache.directory.shared.ldap.entry.Entry;
import org.apache.directory.shared.ldap.entry.EntryAttribute;
import org.apache.directory.shared.ldap.entry.Modification;
import org.apache.directory.shared.ldap.message.control.Control;
import org.apache.directory.shared.ldap.name.DN;
import org.apache.directory.shared.ldap.util.StringTools;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;


/**
 * LDAP BER encoder.
 * 
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class LdapProtocolEncoder extends ProtocolEncoderAdapter
{
    /**
     * Encode a Ldap request and write it to the remote server.
     * 
     * @param session The session containing the LdapMessageContainer
     * @param message The LDAP message we have to encode to a Byte stream
     * @param out The callback we have to invoke when the message has been encoded 
     */
    public void encode( IoSession session, Object message, ProtocolEncoderOutput out ) throws Exception
    {
        ByteBuffer buffer = encodeMessage( ( Message ) message );

        IoBuffer ioBuffer = IoBuffer.wrap( buffer );

        out.write( ioBuffer );
    }


    /**
     * Generate the PDU which contains the encoded object. 
     * 
     * The generation is done in two phases : 
     * - first, we compute the length of each part and the
     * global PDU length 
     * - second, we produce the PDU. 
     * 
     * <pre>
     * 0x30 L1 
     *   | 
     *   +--> 0x02 L2 MessageId  
     *   +--> ProtocolOp 
     *   +--> Controls 
     *   
     * L2 = Length(MessageId)
     * L1 = Length(0x02) + Length(L2) + L2 + Length(ProtocolOp) + Length(Controls)
     * LdapMessageLength = Length(0x30) + Length(L1) + L1
     * </pre>
     * 
     * @param message The message to encode
     * @return A ByteBuffer that contains the PDU
     * @throws EncoderException If anything goes wrong.
     */
    public ByteBuffer encodeMessage( Message message ) throws EncoderException
    {
        int length = computeMessageLength( message );
        ByteBuffer buffer = ByteBuffer.allocate( length );

        try
        {
            try
            {
                // The LdapMessage Sequence
                buffer.put( UniversalTag.SEQUENCE.getValue() );

                // The length has been calculated by the computeLength method
                buffer.put( TLV.getBytes( ( ( AbstractMessage ) message ).getMessageLength() ) );
            }
            catch ( BufferOverflowException boe )
            {
                throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
            }

            // The message Id
            Value.encode( buffer, message.getMessageId() );

            // Add the protocolOp part
            encodeProtocolOp( buffer, message );

            // Do the same thing for Controls, if any.
            Map<String, Control> controls = message.getControls();

            if ( ( controls != null ) && ( controls.size() > 0 ) )
            {
                // Encode the controls
                buffer.put( ( byte ) LdapConstants.CONTROLS_TAG );
                buffer.put( TLV.getBytes( ( ( AbstractMessage ) message ).getControlsLength() ) );

                // Encode each control
                for ( Control control : controls.values() )
                {
                    ( ( CodecControl ) control ).encode( buffer );
                }
            }
        }
        catch ( EncoderException ee )
        {
            MessageEncoderException exception = new MessageEncoderException( message.getMessageId(), ee.getMessage() );

            throw exception;
        }

        buffer.flip();

        return buffer;
    }


    /**
     * Compute the LdapMessage length LdapMessage : 
     * 0x30 L1 
     *   | 
     *   +--> 0x02 0x0(1-4) [0..2^31-1] (MessageId) 
     *   +--> protocolOp 
     *   [+--> Controls] 
     *   
     * MessageId length = Length(0x02) + length(MessageId) + MessageId.length 
     * L1 = length(ProtocolOp) 
     * LdapMessage length = Length(0x30) + Length(L1) + MessageId length + L1
     */
    private int computeMessageLength( Message message )
    {
        // The length of the MessageId. It's the sum of
        // - the tag (0x02), 1 byte
        // - the length of the Id length, 1 byte
        // - the Id length, 1 to 4 bytes
        int ldapMessageLength = 1 + 1 + Value.getNbBytes( message.getMessageId() );

        // Get the protocolOp length
        ldapMessageLength += computeProtocolOpLength( message );

        Map<String, Control> controls = message.getControls();

        // Do the same thing for Controls, if any.
        if ( controls.size() > 0 )
        {
            // Controls :
            // 0xA0 L3
            //   |
            //   +--> 0x30 L4
            //   +--> 0x30 L5
            //   +--> ...
            //   +--> 0x30 Li
            //   +--> ...
            //   +--> 0x30 Ln
            //
            // L3 = Length(0x30) + Length(L5) + L5
            // + Length(0x30) + Length(L6) + L6
            // + ...
            // + Length(0x30) + Length(Li) + Li
            // + ...
            // + Length(0x30) + Length(Ln) + Ln
            //
            // LdapMessageLength = LdapMessageLength + Length(0x90)
            // + Length(L3) + L3
            int controlsSequenceLength = 0;

            // We may have more than one control. ControlsLength is L4.
            for ( Control control : controls.values() )
            {
                controlsSequenceLength += ( ( CodecControl ) control ).computeLength();
            }

            // Computes the controls length
            // 1 + Length.getNbBytes( controlsSequenceLength ) + controlsSequenceLength;
            ( ( AbstractMessage ) message ).setControlsLength( controlsSequenceLength );

            // Now, add the tag and the length of the controls length
            ldapMessageLength += 1 + TLV.getNbBytes( controlsSequenceLength ) + controlsSequenceLength;
        }

        // Store the messageLength
        ( ( AbstractMessage ) message ).setMessageLength( ldapMessageLength );

        // finally, calculate the global message size :
        // length(Tag) + Length(length) + length

        return 1 + ldapMessageLength + TLV.getNbBytes( ldapMessageLength );
    }


    /**
     * Compute the LdapResult length 
     * 
     * LdapResult : 
     * 0x0A 01 resultCode (0..80)
     *   0x04 L1 matchedDN (L1 = Length(matchedDN)) 
     *   0x04 L2 errorMessage (L2 = Length(errorMessage)) 
     *   [0x83 L3] referrals 
     *     | 
     *     +--> 0x04 L4 referral 
     *     +--> 0x04 L5 referral 
     *     +--> ... 
     *     +--> 0x04 Li referral 
     *     +--> ... 
     *     +--> 0x04 Ln referral 
     *     
     * L1 = Length(matchedDN) 
     * L2 = Length(errorMessage) 
     * L3 = n*Length(0x04) + sum(Length(L4) .. Length(Ln)) + sum(L4..Ln) 
     * L4..n = Length(0x04) + Length(Li) + Li 
     * Length(LdapResult) = Length(0x0x0A) +
     *      Length(0x01) + 1 + Length(0x04) + Length(L1) + L1 + Length(0x04) +
     *      Length(L2) + L2 + Length(0x83) + Length(L3) + L3
     */
    private int computeLdapResultLength( LdapResult internalLdapResult )
    {
        LdapResultImpl ldapResult = ( LdapResultImpl ) internalLdapResult;
        int ldapResultLength = 0;

        // The result code : always 3 bytes
        ldapResultLength = 1 + 1 + 1;

        // The matchedDN length
        if ( ldapResult.getMatchedDn() == null )
        {
            ldapResultLength += 1 + 1;
        }
        else
        {
            byte[] matchedDNBytes = StringTools.getBytesUtf8( StringTools
                .trimLeft( ldapResult.getMatchedDn().getName() ) );
            ldapResultLength += 1 + TLV.getNbBytes( matchedDNBytes.length ) + matchedDNBytes.length;
            ldapResult.setMatchedDnBytes( matchedDNBytes );
        }

        // The errorMessage length
        byte[] errorMessageBytes = StringTools.getBytesUtf8( ldapResult.getErrorMessage() );
        ldapResultLength += 1 + TLV.getNbBytes( errorMessageBytes.length ) + errorMessageBytes.length;
        ldapResult.setErrorMessageBytes( errorMessageBytes );

        int referralLength = computeReferralLength( ldapResult.getReferral() );

        if ( referralLength != 0 )
        {
            // The referrals
            ldapResultLength += 1 + TLV.getNbBytes( referralLength ) + referralLength;
        }

        return ldapResultLength;
    }


    /**
     * Encode the LdapResult message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private ByteBuffer encodeLdapResult( ByteBuffer buffer, LdapResult internalLdapResult ) throws EncoderException
    {
        LdapResultImpl ldapResult = ( LdapResultImpl ) internalLdapResult;

        if ( buffer == null )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04023 ) );
        }

        try
        {
            // The result code
            buffer.put( UniversalTag.ENUMERATED.getValue() );
            buffer.put( ( byte ) 1 );
            buffer.put( ( byte ) ldapResult.getResultCode().getValue() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

        // The matchedDN
        Value.encode( buffer, ldapResult.getMatchedDnBytes() );

        // The error message
        Value.encode( buffer, ldapResult.getErrorMessageBytes() );

        // The referrals, if any
        Referral referral = ldapResult.getReferral();

        if ( referral != null )
        {
            encodeReferral( buffer, referral );
        }

        return buffer;
    }


    /**
     * Encode the Referral message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeReferral( ByteBuffer buffer, Referral referral ) throws EncoderException
    {
        Collection<byte[]> ldapUrlsBytes = referral.getLdapUrlsBytes();

        if ( ( ldapUrlsBytes != null ) && ( ldapUrlsBytes.size() != 0 ) )
        {
            // Encode the referrals sequence
            // The referrals length MUST have been computed before !
            buffer.put( ( byte ) LdapConstants.LDAP_RESULT_REFERRAL_SEQUENCE_TAG );
            buffer.put( TLV.getBytes( referral.getReferralLength() ) );

            // Each referral
            for ( byte[] ldapUrlBytes : ldapUrlsBytes )
            {
                // Encode the current referral
                Value.encode( buffer, ldapUrlBytes );
            }
        }
    }


    /**
     * Compute the AbandonRequest length 
     * 
     * AbandonRequest : 
     * 0x50 0x0(1..4) abandoned MessageId 
     * 
     * Length(AbandonRequest) = Length(0x50) + 1 + Length(abandoned MessageId)
     */
    private int computeAbandonRequestLength( AbandonRequestImpl abandonRequest )
    {
        int length = 1 + 1 + Value.getNbBytes( abandonRequest.getAbandoned() );

        return length;
    }


    /**
     * Compute the AddRequest length
     * 
     * AddRequest :
     * 
     * 0x68 L1
     *  |
     *  +--> 0x04 L2 entry
     *  +--> 0x30 L3 (attributes)
     *        |
     *        +--> 0x30 L4-1 (attribute)
     *        |     |
     *        |     +--> 0x04 L5-1 type
     *        |     +--> 0x31 L6-1 (values)
     *        |           |
     *        |           +--> 0x04 L7-1-1 value
     *        |           +--> ...
     *        |           +--> 0x04 L7-1-n value
     *        |
     *        +--> 0x30 L4-2 (attribute)
     *        |     |
     *        |     +--> 0x04 L5-2 type
     *        |     +--> 0x31 L6-2 (values)
     *        |           |
     *        |           +--> 0x04 L7-2-1 value
     *        |           +--> ...
     *        |           +--> 0x04 L7-2-n value
     *        |
     *        +--> ...
     *        |
     *        +--> 0x30 L4-m (attribute)
     *              |
     *              +--> 0x04 L5-m type
     *              +--> 0x31 L6-m (values)
     *                    |
     *                    +--> 0x04 L7-m-1 value
     *                    +--> ...
     *                    +--> 0x04 L7-m-n value
     */
    private int computeAddRequestLength( AddRequestImpl addRequest )
    {
        Entry entry = addRequest.getEntry();

        if ( entry == null )
        {
            throw new IllegalArgumentException( I18n.err( I18n.ERR_04481_ENTRY_NULL_VALUE ) );
        }

        // The entry DN
        int addRequestLength = 1 + TLV.getNbBytes( DN.getNbBytes( entry.getDn() ) ) + DN.getNbBytes( entry.getDn() );

        // The attributes sequence
        int entryLength = 0;

        if ( entry.size() != 0 )
        {
            List<Integer> attributesLength = new LinkedList<Integer>();
            List<Integer> valuesLength = new LinkedList<Integer>();

            // Compute the attributes length
            for ( EntryAttribute attribute : entry )
            {
                int localAttributeLength = 0;
                int localValuesLength = 0;

                // Get the type length
                int idLength = attribute.getId().getBytes().length;
                localAttributeLength = 1 + TLV.getNbBytes( idLength ) + idLength;

                // The values
                if ( attribute.size() != 0 )
                {
                    localValuesLength = 0;

                    for ( org.apache.directory.shared.ldap.entry.Value<?> value : attribute )
                    {
                        int valueLength = value.getBytes().length;
                        localValuesLength += 1 + TLV.getNbBytes( valueLength ) + valueLength;
                    }

                    localAttributeLength += 1 + TLV.getNbBytes( localValuesLength ) + localValuesLength;
                }

                // add the attribute length to the attributes length
                entryLength += 1 + TLV.getNbBytes( localAttributeLength ) + localAttributeLength;

                attributesLength.add( localAttributeLength );
                valuesLength.add( localValuesLength );
            }

            addRequest.setAttributesLength( attributesLength );
            addRequest.setValuesLength( valuesLength );
            addRequest.setEntryLength( entryLength );
        }

        addRequestLength += 1 + TLV.getNbBytes( entryLength ) + entryLength;
        addRequest.setAddRequestLength( addRequestLength );

        // Return the result.
        return 1 + TLV.getNbBytes( addRequestLength ) + addRequestLength;
    }


    /**
     * Compute the AddResponse length 
     * 
     * AddResponse : 
     * 
     * 0x69 L1
     *  |
     *  +--> LdapResult
     * 
     * L1 = Length(LdapResult)
     * 
     * Length(AddResponse) = Length(0x69) + Length(L1) + L1
     */
    private int computeAddResponseLength( AddResponseImpl addResponse )
    {
        int addResponseLength = computeLdapResultLength( addResponse.getLdapResult() );

        addResponse.setAddResponseLength( addResponseLength );

        return 1 + TLV.getNbBytes( addResponseLength ) + addResponseLength;
    }


    /**
     * Compute the BindRequest length 
     * 
     * BindRequest : 
     * <pre>
     * 0x60 L1 
     *   | 
     *   +--> 0x02 0x01 (1..127) version 
     *   +--> 0x04 L2 name 
     *   +--> authentication 
     *   
     * L2 = Length(name)
     * L3/4 = Length(authentication) 
     * Length(BindRequest) = Length(0x60) + Length(L1) + L1 + Length(0x02) + 1 + 1 + 
     *      Length(0x04) + Length(L2) + L2 + Length(authentication)
     * </pre>
     */
    private int computeBindRequestLength( BindRequestImpl bindRequest )
    {
        int bindRequestLength = 1 + 1 + 1; // Initialized with version

        // The name
        bindRequestLength += 1 + TLV.getNbBytes( DN.getNbBytes( bindRequest.getName() ) )
            + DN.getNbBytes( bindRequest.getName() );

        byte[] credentials = bindRequest.getCredentials();

        // The authentication
        if ( bindRequest.isSimple() )
        {
            // Compute a SimpleBind operation
            if ( credentials != null )
            {
                bindRequestLength += 1 + TLV.getNbBytes( credentials.length ) + credentials.length;
            }
            else
            {
                bindRequestLength += 1 + 1;
            }
        }
        else
        {
            byte[] mechanismBytes = StringTools.getBytesUtf8( bindRequest.getSaslMechanism() );
            int saslMechanismLength = 1 + TLV.getNbBytes( mechanismBytes.length ) + mechanismBytes.length;
            int saslCredentialsLength = 0;

            if ( credentials != null )
            {
                saslCredentialsLength = 1 + TLV.getNbBytes( credentials.length ) + credentials.length;
            }

            int saslLength = 1 + TLV.getNbBytes( saslMechanismLength + saslCredentialsLength ) + saslMechanismLength
                + saslCredentialsLength;

            bindRequestLength += saslLength;

            // Store the mechanism and credentials lengths
            bindRequest.setSaslMechanismLength( saslMechanismLength );
            bindRequest.setSaslCredentialsLength( saslCredentialsLength );
        }

        bindRequest.setBindRequestLength( bindRequestLength );

        // Return the result.
        return 1 + TLV.getNbBytes( bindRequestLength ) + bindRequestLength;
    }


    /**
     * Compute the BindResponse length 
     * 
     * BindResponse : 
     * <pre>
     * 0x61 L1 
     *   | 
     *   +--> LdapResult
     *   +--> [serverSaslCreds] 
     *   
     * L1 = Length(LdapResult) [ + Length(serverSaslCreds) ] 
     * Length(BindResponse) = Length(0x61) + Length(L1) + L1
     * </pre>
     */
    private int computeBindResponseLength( BindResponseImpl bindResponse )
    {
        int ldapResultLength = computeLdapResultLength( bindResponse.getLdapResult() );

        int bindResponseLength = ldapResultLength;

        byte[] serverSaslCreds = bindResponse.getServerSaslCreds();

        if ( serverSaslCreds != null )
        {
            bindResponseLength += 1 + TLV.getNbBytes( serverSaslCreds.length ) + serverSaslCreds.length;
        }

        bindResponse.setBindResponseLength( bindResponseLength );

        return 1 + TLV.getNbBytes( bindResponseLength ) + bindResponseLength;
    }


    /**
     * Compute the CompareRequest length 
     * 
     * CompareRequest : 
     * 0x6E L1 
     *   | 
     *   +--> 0x04 L2 entry 
     *   +--> 0x30 L3 (ava) 
     *         | 
     *         +--> 0x04 L4 attributeDesc 
     *         +--> 0x04 L5 assertionValue 
     *         
     * L3 = Length(0x04) + Length(L4) + L4 + Length(0x04) +
     *      Length(L5) + L5 
     * Length(CompareRequest) = Length(0x6E) + Length(L1) + L1 +
     *      Length(0x04) + Length(L2) + L2 + Length(0x30) + Length(L3) + L3
     * 
     * @return The CompareRequest PDU's length
     */
    private int computeCompareRequestLength( CompareRequestImpl compareRequest )
    {
        // The entry DN
        DN entry = compareRequest.getName();
        int compareRequestLength = 1 + TLV.getNbBytes( DN.getNbBytes( entry ) ) + DN.getNbBytes( entry );

        // The attribute value assertion
        byte[] attributeIdBytes = StringTools.getBytesUtf8( compareRequest.getAttributeId() );
        int avaLength = 1 + TLV.getNbBytes( attributeIdBytes.length ) + attributeIdBytes.length;
        compareRequest.setAttrIdBytes( attributeIdBytes );

        if ( compareRequest.getAssertionValue() instanceof BinaryValue )
        {
            byte[] value = compareRequest.getAssertionValue().getBytes();
            avaLength += 1 + TLV.getNbBytes( value.length ) + value.length;
            compareRequest.setAttrValBytes( value );
        }
        else
        {
            byte[] value = StringTools.getBytesUtf8( compareRequest.getAssertionValue().getString() );
            avaLength += 1 + TLV.getNbBytes( value.length ) + value.length;
            compareRequest.setAttrValBytes( value );
        }

        compareRequest.setAvaLength( avaLength );
        compareRequestLength += 1 + TLV.getNbBytes( avaLength ) + avaLength;
        compareRequest.setCompareRequestLength( compareRequestLength );

        return 1 + TLV.getNbBytes( compareRequestLength ) + compareRequestLength;

    }


    /**
     * Compute the CompareResponse length 
     * 
     * CompareResponse :
     * 
     * 0x6F L1
     *  |
     *  +--> LdapResult
     * 
     * L1 = Length(LdapResult)
     * 
     * Length(CompareResponse) = Length(0x6F) + Length(L1) + L1
     */
    private int computeCompareResponseLength( CompareResponseImpl compareResponse )
    {
        int compareResponseLength = computeLdapResultLength( compareResponse.getLdapResult() );

        compareResponse.setCompareResponseLength( compareResponseLength );

        return 1 + TLV.getNbBytes( compareResponseLength ) + compareResponseLength;
    }


    /**
     * Compute the DelRequest length 
     * 
     * DelRequest : 
     * 0x4A L1 entry 
     * 
     * L1 = Length(entry) 
     * Length(DelRequest) = Length(0x4A) + Length(L1) + L1
     */
    private int computeDeleteRequestLength( DeleteRequestImpl deleteRequest )
    {
        // The entry
        return 1 + TLV.getNbBytes( DN.getNbBytes( deleteRequest.getName() ) ) + DN.getNbBytes( deleteRequest.getName() );
    }


    /**
     * Compute the DelResponse length 
     * 
     * DelResponse :
     * 
     * 0x6B L1
     *  |
     *  +--> LdapResult
     * 
     * L1 = Length(LdapResult)
     * 
     * Length(DelResponse) = Length(0x6B) + Length(L1) + L1
     */
    private int computeDeleteResponseLength( DeleteResponseImpl deleteResponse )
    {
        int deleteResponseLength = computeLdapResultLength( deleteResponse.getLdapResult() );

        deleteResponse.setDeleteResponseLength( deleteResponseLength );

        return 1 + TLV.getNbBytes( deleteResponseLength ) + deleteResponseLength;
    }


    /**
     * Compute the ExtendedRequest length
     * 
     * ExtendedRequest :
     * 
     * 0x77 L1
     *  |
     *  +--> 0x80 L2 name
     *  [+--> 0x81 L3 value]
     * 
     * L1 = Length(0x80) + Length(L2) + L2
     *      [+ Length(0x81) + Length(L3) + L3]
     * 
     * Length(ExtendedRequest) = Length(0x77) + Length(L1) + L1
     */
    private int computeExtendedRequestLength( ExtendedRequestImpl extendedRequest )
    {
        byte[] requestNameBytes = StringTools.getBytesUtf8( extendedRequest.getRequestName() );

        extendedRequest.setRequestNameBytes( requestNameBytes );

        int extendedRequestLength = 1 + TLV.getNbBytes( requestNameBytes.length ) + requestNameBytes.length;

        if ( extendedRequest.getRequestValue() != null )
        {
            extendedRequestLength += 1 + TLV.getNbBytes( extendedRequest.getRequestValue().length )
                + extendedRequest.getRequestValue().length;
        }

        extendedRequest.setExtendedRequestLength( extendedRequestLength );

        return 1 + TLV.getNbBytes( extendedRequestLength ) + extendedRequestLength;
    }


    /**
     * Compute the ExtendedResponse length
     * 
     * ExtendedResponse :
     * 
     * 0x78 L1
     *  |
     *  +--> LdapResult
     * [+--> 0x8A L2 name
     * [+--> 0x8B L3 response]]
     * 
     * L1 = Length(LdapResult)
     *      [ + Length(0x8A) + Length(L2) + L2
     *       [ + Length(0x8B) + Length(L3) + L3]]
     * 
     * Length(ExtendedResponse) = Length(0x78) + Length(L1) + L1
     * 
     * @return The ExtendedResponse length
     */
    private int computeExtendedResponseLength( ExtendedResponseImpl extendedResponse )
    {
        int ldapResultLength = computeLdapResultLength( extendedResponse.getLdapResult() );

        int extendedResponseLength = ldapResultLength;

        String id = extendedResponse.getResponseName();

        if ( !StringTools.isEmpty( id ) )
        {
            byte[] idBytes = StringTools.getBytesUtf8( id );
            extendedResponse.setResponseNameBytes( idBytes );
            int idLength = idBytes.length;
            extendedResponseLength += 1 + TLV.getNbBytes( idLength ) + idLength;
        }

        byte[] encodedValue = extendedResponse.getResponseValue();

        if ( encodedValue != null )
        {
            extendedResponseLength += 1 + TLV.getNbBytes( encodedValue.length ) + encodedValue.length;
        }

        extendedResponse.setExtendedResponseLength( extendedResponseLength );

        return 1 + TLV.getNbBytes( extendedResponseLength ) + extendedResponseLength;
    }


    /**
     * Compute the intermediateResponse length
     * 
     * intermediateResponse :
     * 
     * 0x79 L1
     *  |
     * [+--> 0x80 L2 name
     * [+--> 0x81 L3 response]]
     * 
     * L1 = [ + Length(0x80) + Length(L2) + L2
     *      [ + Length(0x81) + Length(L3) + L3]]
     * 
     * Length(IntermediateResponse) = Length(0x79) + Length(L1) + L1
     * 
     * @return The IntermediateResponse length
     */
    private int computeIntermediateResponseLength( IntermediateResponseImpl intermediateResponse )
    {
        int intermediateResponseLength = 0;

        if ( !StringTools.isEmpty( intermediateResponse.getResponseName() ) )
        {
            byte[] responseNameBytes = StringTools.getBytesUtf8( intermediateResponse.getResponseName() );

            int responseNameLength = responseNameBytes.length;
            intermediateResponseLength += 1 + TLV.getNbBytes( responseNameLength ) + responseNameLength;
            intermediateResponse.setResponseNameBytes( responseNameBytes );
        }

        byte[] encodedValue = intermediateResponse.getResponseValue();

        if ( encodedValue != null )
        {
            intermediateResponseLength += 1 + TLV.getNbBytes( encodedValue.length ) + encodedValue.length;
        }

        intermediateResponse.setIntermediateResponseLength( intermediateResponseLength );

        return 1 + TLV.getNbBytes( intermediateResponseLength ) + intermediateResponseLength;
    }


    /**
     * Compute the ModifyRequest length 
     * 
     * ModifyRequest :
     * 
     * 0x66 L1
     *  |
     *  +--> 0x04 L2 object
     *  +--> 0x30 L3 modifications
     *        |
     *        +--> 0x30 L4-1 modification sequence
     *        |     |
     *        |     +--> 0x0A 0x01 (0..2) operation
     *        |     +--> 0x30 L5-1 modification
     *        |           |
     *        |           +--> 0x04 L6-1 type
     *        |           +--> 0x31 L7-1 vals
     *        |                 |
     *        |                 +--> 0x04 L8-1-1 attributeValue
     *        |                 +--> 0x04 L8-1-2 attributeValue
     *        |                 +--> ...
     *        |                 +--> 0x04 L8-1-i attributeValue
     *        |                 +--> ...
     *        |                 +--> 0x04 L8-1-n attributeValue
     *        |
     *        +--> 0x30 L4-2 modification sequence
     *        .     |
     *        .     +--> 0x0A 0x01 (0..2) operation
     *        .     +--> 0x30 L5-2 modification
     *                    |
     *                    +--> 0x04 L6-2 type
     *                    +--> 0x31 L7-2 vals
     *                          |
     *                          +--> 0x04 L8-2-1 attributeValue
     *                          +--> 0x04 L8-2-2 attributeValue
     *                          +--> ...
     *                          +--> 0x04 L8-2-i attributeValue
     *                          +--> ...
     *                          +--> 0x04 L8-2-n attributeValue
     */
    private int computeModifyRequestLength( ModifyRequestImpl modifyRequest )
    {
        // Initialized with name
        int modifyRequestLength = 1 + TLV.getNbBytes( DN.getNbBytes( modifyRequest.getName() ) )
            + DN.getNbBytes( modifyRequest.getName() );

        // All the changes length
        int changesLength = 0;

        Collection<Modification> modifications = modifyRequest.getModifications();

        if ( ( modifications != null ) && ( modifications.size() != 0 ) )
        {
            List<Integer> changeLength = new LinkedList<Integer>();
            List<Integer> modificationLength = new LinkedList<Integer>();
            List<Integer> valuesLength = new LinkedList<Integer>();

            for ( Modification modification : modifications )
            {
                // Modification sequence length initialized with the operation
                int localModificationSequenceLength = 1 + 1 + 1;
                int localValuesLength = 0;

                // Modification length initialized with the type
                int typeLength = modification.getAttribute().getId().length();
                int localModificationLength = 1 + TLV.getNbBytes( typeLength ) + typeLength;

                // Get all the values
                if ( modification.getAttribute().size() != 0 )
                {
                    for ( org.apache.directory.shared.ldap.entry.Value<?> value : modification.getAttribute() )
                    {
                        localValuesLength += 1 + TLV.getNbBytes( value.getBytes().length ) + value.getBytes().length;
                    }
                }

                localModificationLength += 1 + TLV.getNbBytes( localValuesLength ) + localValuesLength;

                // Compute the modificationSequenceLength
                localModificationSequenceLength += 1 + TLV.getNbBytes( localModificationLength )
                    + localModificationLength;

                // Add the tag and the length
                changesLength += 1 + TLV.getNbBytes( localModificationSequenceLength )
                    + localModificationSequenceLength;

                // Store the arrays of values
                valuesLength.add( localValuesLength );
                modificationLength.add( localModificationLength );
                changeLength.add( localModificationSequenceLength );
            }

            // Add the modifications length to the modificationRequestLength
            modifyRequestLength += 1 + TLV.getNbBytes( changesLength ) + changesLength;
            modifyRequest.setChangeLength( changeLength );
            modifyRequest.setModificationLength( modificationLength );
            modifyRequest.setValuesLength( valuesLength );
        }

        modifyRequest.setChangesLength( changesLength );
        modifyRequest.setModifyRequestLength( modifyRequestLength );

        return 1 + TLV.getNbBytes( modifyRequestLength ) + modifyRequestLength;

    }


    /**
     * Compute the ModifyResponse length 
     * 
     * ModifyResponse : 
     * <pre>
     * 0x67 L1 
     *   | 
     *   +--> LdapResult 
     *   
     * L1 = Length(LdapResult) 
     * Length(ModifyResponse) = Length(0x67) + Length(L1) + L1
     * </pre>
     */
    private int computeModifyResponseLength( ModifyResponseImpl modifyResponse )
    {
        int modifyResponseLength = computeLdapResultLength( modifyResponse.getLdapResult() );

        modifyResponse.setModifyResponseLength( modifyResponseLength );

        return 1 + TLV.getNbBytes( modifyResponseLength ) + modifyResponseLength;
    }


    /**
     * Compute the ModifyDNRequest length
     * 
     * ModifyDNRequest :
     * <pre>
     * 0x6C L1
     *  |
     *  +--> 0x04 L2 entry
     *  +--> 0x04 L3 newRDN
     *  +--> 0x01 0x01 (true/false) deleteOldRDN (3 bytes)
     * [+--> 0x80 L4 newSuperior ] 
     * 
     * L2 = Length(0x04) + Length(Length(entry)) + Length(entry) 
     * L3 = Length(0x04) + Length(Length(newRDN)) + Length(newRDN) 
     * L4 = Length(0x80) + Length(Length(newSuperior)) + Length(newSuperior)
     * L1 = L2 + L3 + 3 [+ L4] 
     * 
     * Length(ModifyDNRequest) = Length(0x6C) + Length(L1) + L1
     * </pre>
     * 
     * @return The PDU's length of a ModifyDN Request
     */
    private int computeModifyDnRequestLength( ModifyDnRequestImpl modifyDnResponse )
    {
        int newRdnlength = StringTools.getBytesUtf8( modifyDnResponse.getNewRdn().getName() ).length;

        int modifyDNRequestLength = 1 + TLV.getNbBytes( DN.getNbBytes( modifyDnResponse.getName() ) )
            + DN.getNbBytes( modifyDnResponse.getName() ) + 1 + TLV.getNbBytes( newRdnlength ) + newRdnlength + 1 + 1
            + 1; // deleteOldRDN

        if ( modifyDnResponse.getNewSuperior() != null )
        {
            modifyDNRequestLength += 1 + TLV.getNbBytes( DN.getNbBytes( modifyDnResponse.getNewSuperior() ) )
                + DN.getNbBytes( modifyDnResponse.getNewSuperior() );
        }

        modifyDnResponse.setModifyDnRequestLength( modifyDNRequestLength );

        return 1 + TLV.getNbBytes( modifyDNRequestLength ) + modifyDNRequestLength;
    }


    /**
     * Compute the ModifyDNResponse length 
     * 
     * ModifyDNResponse : 
     * <pre>
     * 0x6D L1 
     *   | 
     *   +--> LdapResult 
     *   
     * L1 = Length(LdapResult) 
     * Length(ModifyDNResponse) = Length(0x6D) + Length(L1) + L1
     * </pre>
     */
    private int computeModifyDnResponseLength( ModifyDnResponseImpl modifyDnResponse )
    {
        int modifyDnResponseLength = computeLdapResultLength( modifyDnResponse.getLdapResult() );

        modifyDnResponse.setModifyDnResponseLength( modifyDnResponseLength );

        return 1 + TLV.getNbBytes( modifyDnResponseLength ) + modifyDnResponseLength;
    }


    private int computeReferralLength( Referral referral )
    {
        if ( referral != null )
        {
            Collection<String> ldapUrls = referral.getLdapUrls();

            if ( ( ldapUrls != null ) && ( ldapUrls.size() != 0 ) )
            {
                int referralLength = 0;

                // Each referral
                for ( String ldapUrl : ldapUrls )
                {
                    byte[] ldapUrlBytes = StringTools.getBytesUtf8( ldapUrl );
                    referralLength += 1 + TLV.getNbBytes( ldapUrlBytes.length ) + ldapUrlBytes.length;
                    referral.addLdapUrlBytes( ldapUrlBytes );
                }

                referral.setReferralLength( referralLength );

                return referralLength;
            }
            else
            {
                return 0;
            }
        }
        else
        {
            return 0;
        }
    }


    /**
     * Compute the SearchRequest length
     * 
     * SearchRequest :
     * <pre>
     * 0x63 L1
     *  |
     *  +--> 0x04 L2 baseObject
     *  +--> 0x0A 0x01 scope
     *  +--> 0x0A 0x01 derefAliases
     *  +--> 0x02 0x0(1..4) sizeLimit
     *  +--> 0x02 0x0(1..4) timeLimit
     *  +--> 0x01 0x01 typesOnly
     *  +--> filter.computeLength()
     *  +--> 0x30 L3 (Attribute description list)
     *        |
     *        +--> 0x04 L4-1 Attribute description 
     *        +--> 0x04 L4-2 Attribute description 
     *        +--> ... 
     *        +--> 0x04 L4-i Attribute description 
     *        +--> ... 
     *        +--> 0x04 L4-n Attribute description 
     *        </pre>
     */
    private int computeSearchRequestLength( SearchRequestImpl searchRequest )
    {
        int searchRequestLength = 0;

        // The baseObject
        searchRequestLength += 1 + TLV.getNbBytes( DN.getNbBytes( searchRequest.getBase() ) )
            + DN.getNbBytes( searchRequest.getBase() );

        // The scope
        searchRequestLength += 1 + 1 + 1;

        // The derefAliases
        searchRequestLength += 1 + 1 + 1;

        // The sizeLimit
        searchRequestLength += 1 + 1 + Value.getNbBytes( searchRequest.getSizeLimit() );

        // The timeLimit
        searchRequestLength += 1 + 1 + Value.getNbBytes( searchRequest.getTimeLimit() );

        // The typesOnly
        searchRequestLength += 1 + 1 + 1;

        // The filter
        searchRequestLength += searchRequest.getCurrentFilter().computeLength();

        // The attributes description list
        int attributeDescriptionListLength = 0;

        if ( ( searchRequest.getAttributes() != null ) && ( searchRequest.getAttributes().size() != 0 ) )
        {
            // Compute the attributes length
            for ( String attribute : searchRequest.getAttributes() )
            {
                // add the attribute length to the attributes length
                int idLength = StringTools.getBytesUtf8( attribute ).length;
                attributeDescriptionListLength += 1 + TLV.getNbBytes( idLength ) + idLength;
            }
        }

        searchRequest.setAttributeDescriptionListLength( attributeDescriptionListLength );

        searchRequestLength += 1 + TLV.getNbBytes( attributeDescriptionListLength ) + attributeDescriptionListLength;

        searchRequest.setSearchRequestLength( searchRequestLength );
        // Return the result.
        return 1 + TLV.getNbBytes( searchRequestLength ) + searchRequestLength;
    }


    /**
     * Compute the SearchResultDone length 
     * 
     * SearchResultDone : 
     * <pre>
     * 0x65 L1 
     *   | 
     *   +--> LdapResult 
     *   
     * L1 = Length(LdapResult) 
     * Length(SearchResultDone) = Length(0x65) + Length(L1) + L1
     * </pre>
     */
    private int computeSearchResultDoneLength( SearchResultDoneImpl searchResultDone )
    {
        int searchResultDoneLength = computeLdapResultLength( searchResultDone.getLdapResult() );

        searchResultDone.setSearchResultDoneLength( searchResultDoneLength );

        return 1 + TLV.getNbBytes( searchResultDoneLength ) + searchResultDoneLength;
    }


    /**
     * Compute the SearchResultEntry length
     * 
     * SearchResultEntry :
     * <pre>
     * 0x64 L1
     *  |
     *  +--> 0x04 L2 objectName
     *  +--> 0x30 L3 (attributes)
     *        |
     *        +--> 0x30 L4-1 (partial attributes list)
     *        |     |
     *        |     +--> 0x04 L5-1 type
     *        |     +--> 0x31 L6-1 (values)
     *        |           |
     *        |           +--> 0x04 L7-1-1 value
     *        |           +--> ...
     *        |           +--> 0x04 L7-1-n value
     *        |
     *        +--> 0x30 L4-2 (partial attributes list)
     *        |     |
     *        |     +--> 0x04 L5-2 type
     *        |     +--> 0x31 L6-2 (values)
     *        |           |
     *        |           +--> 0x04 L7-2-1 value
     *        |           +--> ...
     *        |           +--> 0x04 L7-2-n value
     *        |
     *        +--> ...
     *        |
     *        +--> 0x30 L4-m (partial attributes list)
     *              |
     *              +--> 0x04 L5-m type
     *              +--> 0x31 L6-m (values)
     *                    |
     *                    +--> 0x04 L7-m-1 value
     *                    +--> ...
     *                    +--> 0x04 L7-m-n value
     * </pre>
     */
    private int computeSearchResultEntryLength( SearchResultEntryImpl searchResultEntry )
    {
        DN dn = searchResultEntry.getObjectName();

        byte[] dnBytes = StringTools.getBytesUtf8( dn.getName() );

        // The entry
        int searchResultEntryLength = 1 + TLV.getNbBytes( dnBytes.length ) + dnBytes.length;
        searchResultEntry.setObjectNameBytes( dnBytes );

        // The attributes sequence
        int attributesLength = 0;

        Entry entry = searchResultEntry.getEntry();

        if ( ( entry != null ) && ( entry.size() != 0 ) )
        {
            List<Integer> attributeLength = new LinkedList<Integer>();
            List<Integer> valsLength = new LinkedList<Integer>();

            // Store those lists in the object
            searchResultEntry.setAttributeLength( attributeLength );
            searchResultEntry.setValsLength( valsLength );

            // Compute the attributes length
            for ( EntryAttribute attribute : entry )
            {
                int localAttributeLength = 0;
                int localValuesLength = 0;

                // Get the type length
                int idLength = attribute.getId().getBytes().length;
                localAttributeLength = 1 + TLV.getNbBytes( idLength ) + idLength;

                if ( attribute.size() != 0 )
                {
                    // The values
                    if ( attribute.size() > 0 )
                    {
                        localValuesLength = 0;

                        for ( org.apache.directory.shared.ldap.entry.Value<?> value : attribute )
                        {
                            byte[] binaryValue = value.getBytes();
                            localValuesLength += 1 + TLV.getNbBytes( binaryValue.length ) + binaryValue.length;
                        }

                        localAttributeLength += 1 + TLV.getNbBytes( localValuesLength ) + localValuesLength;
                    }
                    else
                    {
                        // We have to deal with the special wase where
                        // we don't have a value.
                        // It will be encoded as an empty OCTETSTRING,
                        // so it will be two byte slong (0x04 0x00)
                        localAttributeLength += 1 + 1;
                    }
                }
                else
                {
                    // We have no values. We will just have an empty SET OF :
                    // 0x31 0x00
                    localAttributeLength += 1 + 1;
                }

                // add the attribute length to the attributes length
                attributesLength += 1 + TLV.getNbBytes( localAttributeLength ) + localAttributeLength;

                // Store the lengths of the encoded attributes and values
                attributeLength.add( localAttributeLength );
                valsLength.add( localValuesLength );
            }

            // Store the lengths of the entry
            searchResultEntry.setAttributesLength( attributesLength );
        }

        searchResultEntryLength += 1 + TLV.getNbBytes( attributesLength ) + attributesLength;

        // Store the length of the response 
        searchResultEntry.setSearchResultEntryLength( searchResultEntryLength );

        // Return the result.
        return 1 + TLV.getNbBytes( searchResultEntryLength ) + searchResultEntryLength;
    }


    /**
     * Compute the SearchResultReference length
     * 
     * SearchResultReference :
     * <pre>
     * 0x73 L1
     *  |
     *  +--> 0x04 L2 reference
     *  +--> 0x04 L3 reference
     *  +--> ...
     *  +--> 0x04 Li reference
     *  +--> ...
     *  +--> 0x04 Ln reference
     * 
     * L1 = n*Length(0x04) + sum(Length(Li)) + sum(Length(reference[i]))
     * 
     * Length(SearchResultReference) = Length(0x73 + Length(L1) + L1
     * </pre>
     */
    private int computeSearchResultReferenceLength( SearchResultReferenceImpl searchResultReference )
    {
        int searchResultReferenceLength = 0;

        // We may have more than one reference.
        Referral referral = searchResultReference.getReferral();

        int referralLength = computeReferralLength( referral );

        if ( referralLength != 0 )
        {
            searchResultReference.setReferral( referral );

            searchResultReferenceLength = referralLength;
        }

        // Store the length of the response 
        searchResultReference.setSearchResultReferenceLength( searchResultReferenceLength );

        return 1 + TLV.getNbBytes( searchResultReferenceLength ) + searchResultReferenceLength;
    }


    /**
     * Compute the UnBindRequest length 
     * 
     * UnBindRequest : 
     * 0x42 00
     */
    private int computeUnbindRequestLength( UnbindRequestImpl unbindRequest )
    {
        return 2; // Always 2
    }


    /**
     * Encode the Abandon protocolOp part
     */
    private void encodeAbandonRequest( ByteBuffer buffer, AbandonRequestImpl abandonRequest ) throws EncoderException
    {
        try
        {
            // The tag
            buffer.put( LdapConstants.ABANDON_REQUEST_TAG );

            // The length. It has to be evaluated depending on
            // the abandoned messageId value.
            buffer.put( ( byte ) Value.getNbBytes( abandonRequest.getAbandoned() ) );

            // The abandoned messageId
            buffer.put( Value.getBytes( abandonRequest.getAbandoned() ) );
        }
        catch ( BufferOverflowException boe )
        {
            String msg = I18n.err( I18n.ERR_04005 );
            throw new EncoderException( msg );
        }
    }


    /**
     * Encode the AddRequest message to a PDU. 
     * 
     * AddRequest :
     * 
     * 0x68 LL
     *   0x04 LL entry
     *   0x30 LL attributesList
     *     0x30 LL attributeList
     *       0x04 LL attributeDescription
     *       0x31 LL attributeValues
     *         0x04 LL attributeValue
     *         ... 
     *         0x04 LL attributeValue
     *     ... 
     *     0x30 LL attributeList
     *       0x04 LL attributeDescription
     *       0x31 LL attributeValue
     *         0x04 LL attributeValue
     *         ... 
     *         0x04 LL attributeValue 
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeAddRequest( ByteBuffer buffer, AddRequestImpl addRequest ) throws EncoderException
    {
        try
        {
            // The AddRequest Tag
            buffer.put( LdapConstants.ADD_REQUEST_TAG );
            buffer.put( TLV.getBytes( addRequest.getAddRequestLength() ) );

            // The entry
            Value.encode( buffer, DN.getBytes( addRequest.getEntryDn() ) );

            // The attributes sequence
            buffer.put( UniversalTag.SEQUENCE.getValue() );
            buffer.put( TLV.getBytes( addRequest.getEntryLength() ) );

            // The partial attribute list
            Entry entry = addRequest.getEntry();

            if ( entry.size() != 0 )
            {
                int attributeNumber = 0;

                // Compute the attributes length
                for ( EntryAttribute attribute : entry )
                {
                    // The attributes list sequence
                    buffer.put( UniversalTag.SEQUENCE.getValue() );
                    int localAttributeLength = addRequest.getAttributesLength().get( attributeNumber );
                    buffer.put( TLV.getBytes( localAttributeLength ) );

                    // The attribute type
                    Value.encode( buffer, attribute.getId() );

                    // The values
                    buffer.put( UniversalTag.SET.getValue() );
                    int localValuesLength = addRequest.getValuesLength().get( attributeNumber );
                    buffer.put( TLV.getBytes( localValuesLength ) );

                    if ( attribute.size() != 0 )
                    {
                        for ( org.apache.directory.shared.ldap.entry.Value<?> value : attribute )
                        {
                            if ( value.isBinary() )
                            {
                                Value.encode( buffer, value.getBytes() );
                            }
                            else
                            {
                                Value.encode( buffer, value.getString() );
                            }
                        }
                    }

                    // Go to the next attribute number;
                    attributeNumber++;
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( "The PDU buffer size is too small !" );
        }
    }


    /**
     * Encode the AddResponse message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeAddResponse( ByteBuffer buffer, AddResponseImpl addResponse ) throws EncoderException
    {
        try
        {
            // The AddResponse Tag
            buffer.put( LdapConstants.ADD_RESPONSE_TAG );
            buffer.put( TLV.getBytes( addResponse.getAddResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, addResponse.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the BindRequest message to a PDU. 
     * 
     * BindRequest : 
     * <pre>
     * 0x60 LL 
     *   0x02 LL version         0x80 LL simple 
     *   0x04 LL name           /   
     *   authentication.encode() 
     *                          \ 0x83 LL mechanism [0x04 LL credential]
     * </pre>
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeBindRequest( ByteBuffer buffer, BindRequestImpl bindRequest ) throws EncoderException
    {
        try
        {
            // The BindRequest Tag
            buffer.put( LdapConstants.BIND_REQUEST_TAG );
            buffer.put( TLV.getBytes( bindRequest.getBindRequestLength() ) );

        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

        // The version (LDAP V3 only)
        Value.encode( buffer, 3 );

        // The name
        Value.encode( buffer, DN.getBytes( bindRequest.getName() ) );

        byte[] credentials = bindRequest.getCredentials();

        // The authentication
        if ( bindRequest.isSimple() )
        {
            // Simple authentication
            try
            {
                // The simpleAuthentication Tag
                buffer.put( ( byte ) LdapConstants.BIND_REQUEST_SIMPLE_TAG );

                if ( credentials != null )
                {
                    buffer.put( TLV.getBytes( credentials.length ) );

                    if ( credentials.length != 0 )
                    {
                        buffer.put( credentials );
                    }
                }
                else
                {
                    buffer.put( ( byte ) 0 );
                }
            }
            catch ( BufferOverflowException boe )
            {
                String msg = I18n.err( I18n.ERR_04005 );
                throw new EncoderException( msg );
            }
        }
        else
        {
            // SASL Bind
            try
            {
                // The saslAuthentication Tag
                buffer.put( ( byte ) LdapConstants.BIND_REQUEST_SASL_TAG );

                byte[] mechanismBytes = StringTools.getBytesUtf8( bindRequest.getSaslMechanism() );

                buffer.put( TLV
                    .getBytes( bindRequest.getSaslMechanismLength() + bindRequest.getSaslCredentialsLength() ) );

                Value.encode( buffer, mechanismBytes );

                if ( credentials != null )
                {
                    Value.encode( buffer, credentials );
                }
            }
            catch ( BufferOverflowException boe )
            {
                String msg = I18n.err( I18n.ERR_04005 );
                throw new EncoderException( msg );
            }
        }
    }


    /**
     * Encode the BindResponse message to a PDU.
     * 
     * BindResponse :
     * <pre>
     * LdapResult.encode 
     * [0x87 LL serverSaslCreds]
     * </pre>
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeBindResponse( ByteBuffer bb, BindResponseImpl bindResponse ) throws EncoderException
    {
        try
        {
            // The BindResponse Tag
            bb.put( LdapConstants.BIND_RESPONSE_TAG );
            bb.put( TLV.getBytes( bindResponse.getBindResponseLength() ) );

            // The LdapResult
            encodeLdapResult( bb, bindResponse.getLdapResult() );

            // The serverSaslCredential, if any
            byte[] serverSaslCreds = bindResponse.getServerSaslCreds();

            if ( serverSaslCreds != null )
            {
                bb.put( ( byte ) LdapConstants.SERVER_SASL_CREDENTIAL_TAG );

                bb.put( TLV.getBytes( serverSaslCreds.length ) );

                if ( serverSaslCreds.length != 0 )
                {
                    bb.put( serverSaslCreds );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the CompareRequest message to a PDU. 
     * 
     * CompareRequest : 
     *   0x6E LL 
     *     0x04 LL entry 
     *     0x30 LL attributeValueAssertion 
     *       0x04 LL attributeDesc 
     *       0x04 LL assertionValue
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeCompareRequest( ByteBuffer buffer, CompareRequestImpl compareRequest ) throws EncoderException
    {
        try
        {
            // The CompareRequest Tag
            buffer.put( LdapConstants.COMPARE_REQUEST_TAG );
            buffer.put( TLV.getBytes( compareRequest.getCompareRequestLength() ) );

            // The entry
            Value.encode( buffer, DN.getBytes( compareRequest.getName() ) );

            // The attributeValueAssertion sequence Tag
            buffer.put( UniversalTag.SEQUENCE.getValue() );
            buffer.put( TLV.getBytes( compareRequest.getAvaLength() ) );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

        // The attributeDesc
        Value.encode( buffer, compareRequest.getAttrIdBytes() );

        // The assertionValue
        Value.encode( buffer, ( byte[] ) compareRequest.getAttrValBytes() );
    }


    /**
     * Encode the CompareResponse message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeCompareResponse( ByteBuffer buffer, CompareResponseImpl compareResponse )
        throws EncoderException
    {
        try
        {
            // The CompareResponse Tag
            buffer.put( LdapConstants.COMPARE_RESPONSE_TAG );
            buffer.put( TLV.getBytes( compareResponse.getCompareResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, compareResponse.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the DelRequest message to a PDU. 
     * 
     * DelRequest : 
     * 0x4A LL entry
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeDeleteRequest( ByteBuffer buffer, DeleteRequestImpl deleteRequest ) throws EncoderException
    {
        try
        {
            // The DelRequest Tag
            buffer.put( LdapConstants.DEL_REQUEST_TAG );

            // The entry
            buffer.put( TLV.getBytes( DN.getNbBytes( deleteRequest.getName() ) ) );
            buffer.put( DN.getBytes( deleteRequest.getName() ) );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the DelResponse message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeDeleteResponse( ByteBuffer buffer, DeleteResponseImpl deleteResponse ) throws EncoderException
    {
        try
        {
            // The DelResponse Tag
            buffer.put( LdapConstants.DEL_RESPONSE_TAG );
            buffer.put( TLV.getBytes( deleteResponse.getDeleteResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, deleteResponse.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the ExtendedRequest message to a PDU. 
     * 
     * ExtendedRequest :
     * 
     * 0x80 LL resquest name
     * [0x81 LL request value]
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeExtendedRequest( ByteBuffer buffer, ExtendedRequestImpl extendedRequest )
        throws EncoderException
    {
        try
        {
            // The BindResponse Tag
            buffer.put( LdapConstants.EXTENDED_REQUEST_TAG );
            buffer.put( TLV.getBytes( extendedRequest.getExtendedRequestLength() ) );

            // The requestName, if any
            if ( extendedRequest.getRequestNameBytes() == null )
            {
                throw new EncoderException( I18n.err( I18n.ERR_04043 ) );
            }

            buffer.put( ( byte ) LdapConstants.EXTENDED_REQUEST_NAME_TAG );
            buffer.put( TLV.getBytes( extendedRequest.getRequestNameBytes().length ) );

            if ( extendedRequest.getRequestNameBytes().length != 0 )
            {
                buffer.put( extendedRequest.getRequestNameBytes() );
            }

            // The requestValue, if any
            if ( extendedRequest.getRequestValue() != null )
            {
                buffer.put( ( byte ) LdapConstants.EXTENDED_REQUEST_VALUE_TAG );

                buffer.put( TLV.getBytes( extendedRequest.getRequestValue().length ) );

                if ( extendedRequest.getRequestValue().length != 0 )
                {
                    buffer.put( extendedRequest.getRequestValue() );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

    }


    /**
     * Encode the ExtendedResponse message to a PDU. 
     * ExtendedResponse :
     * LdapResult.encode()
     * [0x8A LL response name]
     * [0x8B LL response]
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeExtendedResponse( ByteBuffer buffer, ExtendedResponseImpl extendedResponse )
        throws EncoderException
    {
        try
        {
            // The ExtendedResponse Tag
            buffer.put( LdapConstants.EXTENDED_RESPONSE_TAG );
            buffer.put( TLV.getBytes( extendedResponse.getExtendedResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, extendedResponse.getLdapResult() );

            // The ID, if any
            byte[] idBytes = extendedResponse.getResponseNameBytes();

            if ( idBytes != null )
            {
                buffer.put( ( byte ) LdapConstants.EXTENDED_RESPONSE_RESPONSE_NAME_TAG );
                buffer.put( TLV.getBytes( idBytes.length ) );

                if ( idBytes.length != 0 )
                {
                    buffer.put( idBytes );
                }
            }

            // The encodedValue, if any
            byte[] encodedValue = extendedResponse.getResponseValue();

            if ( encodedValue != null )
            {
                buffer.put( ( byte ) LdapConstants.EXTENDED_RESPONSE_RESPONSE_TAG );

                buffer.put( TLV.getBytes( encodedValue.length ) );

                if ( encodedValue.length != 0 )
                {
                    buffer.put( encodedValue );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the IntermediateResponse message to a PDU. 
     * IntermediateResponse :
     *   0x79 LL
     *     [0x80 LL response name]
     *     [0x81 LL responseValue]
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeIntermediateResponse( ByteBuffer buffer, IntermediateResponseImpl intermediateResponse )
        throws EncoderException
    {
        try
        {
            // The ExtendedResponse Tag
            buffer.put( LdapConstants.INTERMEDIATE_RESPONSE_TAG );
            buffer.put( TLV.getBytes( intermediateResponse.getIntermediateResponseLength() ) );

            // The responseName, if any
            byte[] responseNameBytes = intermediateResponse.getResponseNameBytes();

            if ( ( responseNameBytes != null ) && ( responseNameBytes.length != 0 ) )
            {
                buffer.put( ( byte ) LdapConstants.INTERMEDIATE_RESPONSE_NAME_TAG );
                buffer.put( TLV.getBytes( responseNameBytes.length ) );
                buffer.put( responseNameBytes );
            }

            // The encodedValue, if any
            byte[] encodedValue = intermediateResponse.getResponseValue();

            if ( encodedValue != null )
            {
                buffer.put( ( byte ) LdapConstants.INTERMEDIATE_RESPONSE_VALUE_TAG );

                buffer.put( TLV.getBytes( encodedValue.length ) );

                if ( encodedValue.length != 0 )
                {
                    buffer.put( encodedValue );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the ModifyRequest message to a PDU. 
     * 
     * ModifyRequest : 
     * <pre>
     * 0x66 LL
     *   0x04 LL object
     *   0x30 LL modifiations
     *     0x30 LL modification sequence
     *       0x0A 0x01 operation
     *       0x30 LL modification
     *         0x04 LL type
     *         0x31 LL vals
     *           0x04 LL attributeValue
     *           ... 
     *           0x04 LL attributeValue
     *     ... 
     *     0x30 LL modification sequence
     *       0x0A 0x01 operation
     *       0x30 LL modification
     *         0x04 LL type
     *         0x31 LL vals
     *           0x04 LL attributeValue
     *           ... 
     *           0x04 LL attributeValue
     * </pre>
     * 
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeModifyRequest( ByteBuffer buffer, ModifyRequestImpl modifyRequest ) throws EncoderException
    {
        try
        {
            // The AddRequest Tag
            buffer.put( LdapConstants.MODIFY_REQUEST_TAG );
            buffer.put( TLV.getBytes( modifyRequest.getModifyRequestLength() ) );

            // The entry
            Value.encode( buffer, DN.getBytes( modifyRequest.getName() ) );

            // The modifications sequence
            buffer.put( UniversalTag.SEQUENCE.getValue() );
            buffer.put( TLV.getBytes( modifyRequest.getChangesLength() ) );

            // The modifications list
            Collection<Modification> modifications = modifyRequest.getModifications();

            if ( ( modifications != null ) && ( modifications.size() != 0 ) )
            {
                int modificationNumber = 0;

                // Compute the modifications length
                for ( Modification modification : modifications )
                {
                    // The modification sequence
                    buffer.put( UniversalTag.SEQUENCE.getValue() );
                    int localModificationSequenceLength = modifyRequest.getChangeLength().get( modificationNumber );
                    buffer.put( TLV.getBytes( localModificationSequenceLength ) );

                    // The operation. The value has to be changed, it's not
                    // the same value in DirContext and in RFC 2251.
                    buffer.put( UniversalTag.ENUMERATED.getValue() );
                    buffer.put( ( byte ) 1 );
                    buffer.put( ( byte ) modification.getOperation().getValue() );

                    // The modification
                    buffer.put( UniversalTag.SEQUENCE.getValue() );
                    int localModificationLength = modifyRequest.getModificationLength().get( modificationNumber );
                    buffer.put( TLV.getBytes( localModificationLength ) );

                    // The modification type
                    Value.encode( buffer, modification.getAttribute().getId() );

                    // The values
                    buffer.put( UniversalTag.SET.getValue() );
                    int localValuesLength = modifyRequest.getValuesLength().get( modificationNumber );
                    buffer.put( TLV.getBytes( localValuesLength ) );

                    if ( modification.getAttribute().size() != 0 )
                    {
                        for ( org.apache.directory.shared.ldap.entry.Value<?> value : modification.getAttribute() )
                        {
                            if ( !value.isBinary() )
                            {
                                Value.encode( buffer, value.getString() );
                            }
                            else
                            {
                                Value.encode( buffer, value.getBytes() );
                            }
                        }
                    }

                    // Go to the next modification number;
                    modificationNumber++;
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the ModifyResponse message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeModifyResponse( ByteBuffer buffer, ModifyResponseImpl modifyResponse ) throws EncoderException
    {
        try
        {
            // The ModifyResponse Tag
            buffer.put( LdapConstants.MODIFY_RESPONSE_TAG );
            buffer.put( TLV.getBytes( modifyResponse.getModifyResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, modifyResponse.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the ModifyDNRequest message to a PDU. 
     * 
     * ModifyDNRequest :
     * <pre>
     * 0x6C LL
     *   0x04 LL entry
     *   0x04 LL newRDN
     *   0x01 0x01 deleteOldRDN
     *   [0x80 LL newSuperior]
     * </pre>
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeModifyDnRequest( ByteBuffer buffer, ModifyDnRequestImpl modifyDnRequest )
        throws EncoderException
    {
        try
        {
            // The ModifyDNRequest Tag
            buffer.put( LdapConstants.MODIFY_DN_REQUEST_TAG );
            buffer.put( TLV.getBytes( modifyDnRequest.getModifyDnResponseLength() ) );

            // The entry

            Value.encode( buffer, DN.getBytes( modifyDnRequest.getName() ) );

            // The newRDN
            Value.encode( buffer, modifyDnRequest.getNewRdn().getName() );

            // The flag deleteOldRdn
            Value.encode( buffer, modifyDnRequest.getDeleteOldRdn() );

            // The new superior, if any
            if ( modifyDnRequest.getNewSuperior() != null )
            {
                // Encode the reference
                buffer.put( ( byte ) LdapConstants.MODIFY_DN_REQUEST_NEW_SUPERIOR_TAG );

                int newSuperiorLength = DN.getNbBytes( modifyDnRequest.getNewSuperior() );

                buffer.put( TLV.getBytes( newSuperiorLength ) );

                if ( newSuperiorLength != 0 )
                {
                    buffer.put( DN.getBytes( modifyDnRequest.getNewSuperior() ) );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

    }


    /**
     * Encode the ModifyDnResponse message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeModifyDnResponse( ByteBuffer buffer, ModifyDnResponseImpl modifyDnResponse )
        throws EncoderException
    {
        try
        {
            // The ModifyResponse Tag
            buffer.put( LdapConstants.MODIFY_DN_RESPONSE_TAG );
            buffer.put( TLV.getBytes( modifyDnResponse.getModifyDnResponseLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, modifyDnResponse.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the SearchRequest message to a PDU.
     * 
     * SearchRequest :
     * <pre>
     * 0x63 LL
     *   0x04 LL baseObject
     *   0x0A 01 scope
     *   0x0A 01 derefAliases
     *   0x02 0N sizeLimit
     *   0x02 0N timeLimit
     *   0x01 0x01 typesOnly
     *   filter.encode()
     *   0x30 LL attributeDescriptionList
     *     0x04 LL attributeDescription
     *     ... 
     *     0x04 LL attributeDescription
     * </pre>
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeSearchRequest( ByteBuffer buffer, SearchRequestImpl searchRequest ) throws EncoderException
    {
        try
        {
            // The SearchRequest Tag
            buffer.put( LdapConstants.SEARCH_REQUEST_TAG );
            buffer.put( TLV.getBytes( searchRequest.getSearchRequestLength() ) );

            // The baseObject
            Value.encode( buffer, DN.getBytes( searchRequest.getBase() ) );

            // The scope
            Value.encodeEnumerated( buffer, searchRequest.getScope().getScope() );

            // The derefAliases
            Value.encodeEnumerated( buffer, searchRequest.getDerefAliases().getValue() );

            // The sizeLimit
            Value.encode( buffer, searchRequest.getSizeLimit() );

            // The timeLimit
            Value.encode( buffer, searchRequest.getTimeLimit() );

            // The typesOnly
            Value.encode( buffer, searchRequest.getTypesOnly() );

            // The filter
            searchRequest.getCurrentFilter().encode( buffer );

            // The attributeDescriptionList
            buffer.put( UniversalTag.SEQUENCE.getValue() );
            buffer.put( TLV.getBytes( searchRequest.getAttributeDescriptionListLength() ) );

            if ( ( searchRequest.getAttributes() != null ) && ( searchRequest.getAttributes().size() != 0 ) )
            {
                // encode each attribute
                for ( String attribute : searchRequest.getAttributes() )
                {
                    Value.encode( buffer, attribute );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }

    }


    /**
     * Encode the SearchResultDone message to a PDU.
     * 
     * @param buffer The buffer where to put the PDU
     */
    private void encodeSearchResultDone( ByteBuffer buffer, SearchResultDoneImpl searchResultDone )
        throws EncoderException
    {
        try
        {
            // The searchResultDone Tag
            buffer.put( LdapConstants.SEARCH_RESULT_DONE_TAG );
            buffer.put( TLV.getBytes( searchResultDone.getSearchResultDoneLength() ) );

            // The LdapResult
            encodeLdapResult( buffer, searchResultDone.getLdapResult() );
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the SearchResultEntry message to a PDU.
     * 
     * SearchResultEntry :
     * <pre>
     * 0x64 LL
     *   0x04 LL objectName
     *   0x30 LL attributes
     *     0x30 LL partialAttributeList
     *       0x04 LL type
     *       0x31 LL vals
     *         0x04 LL attributeValue
     *         ... 
     *         0x04 LL attributeValue
     *     ... 
     *     0x30 LL partialAttributeList
     *       0x04 LL type
     *       0x31 LL vals
     *         0x04 LL attributeValue
     *         ... 
     *         0x04 LL attributeValue 
     * </pre>
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeSearchResultEntry( ByteBuffer buffer, SearchResultEntryImpl searchResultEntry )
        throws EncoderException
    {
        try
        {
            // The SearchResultEntry Tag
            buffer.put( LdapConstants.SEARCH_RESULT_ENTRY_TAG );
            buffer.put( TLV.getBytes( searchResultEntry.getSearchResultEntryLength() ) );

            // The objectName
            Value.encode( buffer, searchResultEntry.getObjectNameBytes() );

            // The attributes sequence
            buffer.put( UniversalTag.SEQUENCE.getValue() );
            buffer.put( TLV.getBytes( searchResultEntry.getAttributesLength() ) );

            // The partial attribute list
            Entry entry = searchResultEntry.getEntry();

            if ( ( entry != null ) && ( entry.size() != 0 ) )
            {
                int attributeNumber = 0;

                // Compute the attributes length
                for ( EntryAttribute attribute : entry )
                {
                    // The partial attribute list sequence
                    buffer.put( UniversalTag.SEQUENCE.getValue() );
                    int localAttributeLength = searchResultEntry.getAttributeLength().get( attributeNumber );
                    buffer.put( TLV.getBytes( localAttributeLength ) );

                    // The attribute type
                    Value.encode( buffer, Asn1StringUtils.asciiStringToByte( attribute.getUpId() ) );

                    // The values
                    buffer.put( UniversalTag.SET.getValue() );
                    int localValuesLength = searchResultEntry.getValsLength().get( attributeNumber );
                    buffer.put( TLV.getBytes( localValuesLength ) );

                    if ( attribute.size() > 0 )
                    {
                        for ( org.apache.directory.shared.ldap.entry.Value<?> value : attribute )
                        {
                            if ( !value.isBinary() )
                            {
                                Value.encode( buffer, value.getString() );
                            }
                            else
                            {
                                Value.encode( buffer, value.getBytes() );
                            }
                        }
                    }

                    // Go to the next attribute number;
                    attributeNumber++;
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Encode the Unbind protocolOp part
     */
    private void encodeUnbindRequest( ByteBuffer buffer, UnbindRequestImpl unbindRequest ) throws EncoderException
    {
        try
        {
            // The tag
            buffer.put( LdapConstants.UNBIND_REQUEST_TAG );

            // The length is always null.
            buffer.put( ( byte ) 0 );
        }
        catch ( BufferOverflowException boe )
        {
            String msg = I18n.err( I18n.ERR_04005 );
            throw new EncoderException( msg );
        }
    }


    /**
     * Encode the SearchResultReference message to a PDU.
     * 
     * SearchResultReference :
     * <pre>
     * 0x73 LL
     *   0x04 LL reference
     *   [0x04 LL reference]*
     * </pre>
     * @param buffer The buffer where to put the PDU
     * @return The PDU.
     */
    private void encodeSearchResultReference( ByteBuffer buffer, SearchResultReferenceImpl searchResultReference )
        throws EncoderException
    {
        try
        {
            // The SearchResultReference Tag
            buffer.put( LdapConstants.SEARCH_RESULT_REFERENCE_TAG );
            buffer.put( TLV.getBytes( searchResultReference.getSearchResultReferenceLength() ) );

            // The referrals, if any
            Referral referral = searchResultReference.getReferral();

            if ( referral != null )
            {
                // Each referral
                for ( byte[] ldapUrlBytes : referral.getLdapUrlsBytes() )
                {
                    // Encode the current referral
                    Value.encode( buffer, ldapUrlBytes );
                }
            }
        }
        catch ( BufferOverflowException boe )
        {
            throw new EncoderException( I18n.err( I18n.ERR_04005 ) );
        }
    }


    /**
     * Compute the protocolOp length 
     */
    private int computeProtocolOpLength( Message message )
    {
        switch ( message.getType() )
        {
            case ABANDON_REQUEST:
                return computeAbandonRequestLength( ( AbandonRequestImpl ) message );

            case ADD_REQUEST:
                return computeAddRequestLength( ( AddRequestImpl ) message );

            case ADD_RESPONSE:
                return computeAddResponseLength( ( AddResponseImpl ) message );

            case BIND_REQUEST:
                return computeBindRequestLength( ( BindRequestImpl ) message );

            case BIND_RESPONSE:
                return computeBindResponseLength( ( BindResponseImpl ) message );

            case COMPARE_REQUEST:
                return computeCompareRequestLength( ( CompareRequestImpl ) message );

            case COMPARE_RESPONSE:
                return computeCompareResponseLength( ( CompareResponseImpl ) message );

            case DEL_REQUEST:
                return computeDeleteRequestLength( ( DeleteRequestImpl ) message );

            case DEL_RESPONSE:
                return computeDeleteResponseLength( ( DeleteResponseImpl ) message );

            case EXTENDED_REQUEST:
                return computeExtendedRequestLength( ( ExtendedRequestImpl ) message );

            case EXTENDED_RESPONSE:
                return computeExtendedResponseLength( ( ExtendedResponseImpl ) message );

            case INTERMEDIATE_RESPONSE:
                return computeIntermediateResponseLength( ( IntermediateResponseImpl ) message );

            case MODIFY_REQUEST:
                return computeModifyRequestLength( ( ModifyRequestImpl ) message );

            case MODIFY_RESPONSE:
                return computeModifyResponseLength( ( ModifyResponseImpl ) message );

            case MODIFYDN_REQUEST:
                return computeModifyDnRequestLength( ( ModifyDnRequestImpl ) message );

            case MODIFYDN_RESPONSE:
                return computeModifyDnResponseLength( ( ModifyDnResponseImpl ) message );

            case SEARCH_REQUEST:
                return computeSearchRequestLength( ( SearchRequestImpl ) message );

            case SEARCH_RESULT_DONE:
                return computeSearchResultDoneLength( ( SearchResultDoneImpl ) message );

            case SEARCH_RESULT_ENTRY:
                return computeSearchResultEntryLength( ( SearchResultEntryImpl ) message );

            case SEARCH_RESULT_REFERENCE:
                return computeSearchResultReferenceLength( ( SearchResultReferenceImpl ) message );

            case UNBIND_REQUEST:
                return computeUnbindRequestLength( ( UnbindRequestImpl ) message );

            default:
                return 0;
        }
    }


    private void encodeProtocolOp( ByteBuffer bb, Message message ) throws EncoderException
    {
        switch ( message.getType() )
        {
            case ABANDON_REQUEST:
                encodeAbandonRequest( bb, ( AbandonRequestImpl ) message );
                break;

            case ADD_REQUEST:
                encodeAddRequest( bb, ( AddRequestImpl ) message );
                break;

            case ADD_RESPONSE:
                encodeAddResponse( bb, ( AddResponseImpl ) message );
                break;

            case BIND_REQUEST:
                encodeBindRequest( bb, ( BindRequestImpl ) message );
                break;

            case BIND_RESPONSE:
                encodeBindResponse( bb, ( BindResponseImpl ) message );
                break;

            case COMPARE_REQUEST:
                encodeCompareRequest( bb, ( CompareRequestImpl ) message );
                break;

            case COMPARE_RESPONSE:
                encodeCompareResponse( bb, ( CompareResponseImpl ) message );
                break;

            case DEL_REQUEST:
                encodeDeleteRequest( bb, ( DeleteRequestImpl ) message );
                break;

            case DEL_RESPONSE:
                encodeDeleteResponse( bb, ( DeleteResponseImpl ) message );
                break;

            case EXTENDED_REQUEST:
                encodeExtendedRequest( bb, ( ExtendedRequestImpl ) message );
                break;

            case EXTENDED_RESPONSE:
                encodeExtendedResponse( bb, ( ExtendedResponseImpl ) message );
                break;

            case INTERMEDIATE_RESPONSE:
                encodeIntermediateResponse( bb, ( IntermediateResponseImpl ) message );
                break;

            case MODIFY_REQUEST:
                encodeModifyRequest( bb, ( ModifyRequestImpl ) message );
                break;

            case MODIFY_RESPONSE:
                encodeModifyResponse( bb, ( ModifyResponseImpl ) message );
                break;

            case MODIFYDN_REQUEST:
                encodeModifyDnRequest( bb, ( ModifyDnRequestImpl ) message );
                break;

            case MODIFYDN_RESPONSE:
                encodeModifyDnResponse( bb, ( ModifyDnResponseImpl ) message );
                break;

            case SEARCH_REQUEST:
                encodeSearchRequest( bb, ( SearchRequestImpl ) message );
                break;

            case SEARCH_RESULT_DONE:
                encodeSearchResultDone( bb, ( SearchResultDoneImpl ) message );
                break;

            case SEARCH_RESULT_ENTRY:
                encodeSearchResultEntry( bb, ( SearchResultEntryImpl ) message );
                break;

            case SEARCH_RESULT_REFERENCE:
                encodeSearchResultReference( bb, ( SearchResultReferenceImpl ) message );
                break;

            case UNBIND_REQUEST:
                encodeUnbindRequest( bb, ( UnbindRequestImpl ) message );
                break;
        }
    }
}