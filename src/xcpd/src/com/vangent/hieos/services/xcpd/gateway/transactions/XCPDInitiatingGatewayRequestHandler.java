/*
 * This code is subject to the HIEOS License, Version 1.0
 *
 * Copyright(c) 2010 Vangent, Inc.  All rights reserved.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vangent.hieos.services.xcpd.gateway.transactions;

import com.vangent.hieos.hl7v3util.client.XCPDGatewayClient;
import com.vangent.hieos.hl7v3util.model.message.HL7V3Message;
import com.vangent.hieos.hl7v3util.model.message.HL7V3MessageBuilderHelper;
import com.vangent.hieos.hl7v3util.model.message.MCCI_IN000002UV01_Message;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201306UV02_Message_Builder;
import com.vangent.hieos.hl7v3util.model.message.MCCI_IN000002UV01_Message_Builder;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201301UV02_Message;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201305UV02_Message;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201305UV02_Message_Builder;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201306UV02_Message;
import com.vangent.hieos.hl7v3util.model.subject.SubjectBuilder;
import com.vangent.hieos.hl7v3util.model.subject.SubjectSearchCriteriaBuilder;
import com.vangent.hieos.hl7v3util.model.exception.ModelBuilderException;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201309UV02_Message;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201310UV02_Message;
import com.vangent.hieos.hl7v3util.model.message.PRPA_IN201310UV02_Message_Builder;
import com.vangent.hieos.hl7v3util.model.subject.DeviceInfo;
import com.vangent.hieos.hl7v3util.model.subject.Subject;
import com.vangent.hieos.hl7v3util.model.subject.SubjectIdentifier;
import com.vangent.hieos.hl7v3util.model.subject.SubjectIdentifierDomain;
import com.vangent.hieos.hl7v3util.model.subject.SubjectSearchCriteria;
import com.vangent.hieos.hl7v3util.model.subject.SubjectSearchResponse;
import com.vangent.hieos.services.xcpd.gateway.exception.XCPDException;
import com.vangent.hieos.services.xcpd.patientcorrelationcache.exception.PatientCorrelationCacheException;
import com.vangent.hieos.services.xcpd.patientcorrelationcache.model.PatientCorrelationCacheEntry;
import com.vangent.hieos.services.xcpd.patientcorrelationcache.service.PatientCorrelationCacheService;
import com.vangent.hieos.xutil.xconfig.XConfig;
import com.vangent.hieos.xutil.xconfig.XConfigActor;
import com.vangent.hieos.xutil.xconfig.XConfigObject;
import com.vangent.hieos.xutil.xconfig.XConfigTransaction;
import com.vangent.hieos.xutil.xlog.client.XLogMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.log4j.Logger;

/**
 * Class to handle all requests to XCPD Initiating Gateway (IG).
 *
 * @author Bernie Thuman
 */
public class XCPDInitiatingGatewayRequestHandler extends XCPDGatewayRequestHandler {

    // Type type of message received.
    public enum MessageType {

        PatientRegistryGetIdentifiersQuery,
        PatientRegistryRecordAdded,
        PatientRegistryFindCandidatesQuery
    };
    private final static Logger logger = Logger.getLogger(XCPDInitiatingGatewayRequestHandler.class);
    private final static int DEFAULT_MINIMUM_DEGREE_MATCH_PERCENTAGE = 90;
    private static XConfigActor _pdsConfig = null;
    //private static XConfigActor _pixConfig = null;
    private static List<XConfigActor> _xcpdRespondingGateways = null;
    private static ExecutorService _executor = null;  // Only one of these shared across all web requests.

    /**
     * Constructor for handling requests to XCPD Initiating Gateway.
     *
     * @param log_message  Place to put internal log messages.
     */
    public XCPDInitiatingGatewayRequestHandler(XLogMessage log_message) {
        super(log_message, XCPDInitiatingGatewayRequestHandler.GatewayType.InitiatingGateway);
    }

    /**
     *
     * @param request
     * @param messageType
     * @return
     * @throws AxisFault
     */
    public OMElement run(OMElement request, MessageType messageType) throws AxisFault {
        HL7V3Message result = null;
        log_message.setPass(true);  // Hope for the best.
        switch (messageType) {
            case PatientRegistryFindCandidatesQuery:
                result = this.processPatientRegistryFindCandidatesQuery(new PRPA_IN201305UV02_Message(request));
                break;
            case PatientRegistryGetIdentifiersQuery:
                result = this.processPatientRegistryGetIdentifiersQuery(new PRPA_IN201309UV02_Message(request));
                break;
            case PatientRegistryRecordAdded:
                result = this.processPatientRegistryRecordAdded(new PRPA_IN201301UV02_Message(request));
                break;
        }
        if (result != null) {
            if (log_message.isLogEnabled()) {
                log_message.addOtherParam("Response", result.getMessageNode());
            }
        }
        return (result != null) ? result.getMessageNode() : null;
    }

    /**
     * 
     * @param request
     * @return
     * @throws AxisFault
     */
    private PRPA_IN201306UV02_Message processPatientRegistryFindCandidatesQuery(PRPA_IN201305UV02_Message request) throws AxisFault {
        this.validateHL7V3Message(request);
        PRPA_IN201306UV02_Message result = null;
        SubjectSearchResponse patientDiscoverySearchResponse = null;
        String errorText = null;  // Hope for the best.
        try {
            SubjectSearchCriteria subjectSearchCriteria = this.getSubjectSearchCriteria(request);
            this.validateSearchCriteriaRequest(subjectSearchCriteria);
            // TBD: Should we see if we know about this patient first?
            // DeviceInfo senderDeviceInfo = this.getDeviceInfo();
            // SubjectSearchResponse pdqSearchResponse = this.findCandidatesQuery(senderDeviceInfo, subjectSearchCriteria);

            // Assume that PDQ request has valid patient id and demographics (skip PDQ validation).
            patientDiscoverySearchResponse = this.performCrossGatewayPatientDiscovery(subjectSearchCriteria);
        } catch (XCPDException ex) {
            errorText = ex.getMessage();
        }
        result = this.getPatientRegistryFindCandidatesQueryResponse(request, patientDiscoverySearchResponse, errorText);
        this.log(errorText);
        this.validateHL7V3Message(result);
        return result;
    }

    /**
     * 
     * @param request
     * @return
     * @throws AxisFault
     */
    private PRPA_IN201310UV02_Message processPatientRegistryGetIdentifiersQuery(PRPA_IN201309UV02_Message request) throws AxisFault {
        this.validateHL7V3Message(request);
        PRPA_IN201310UV02_Message result = null;
        SubjectSearchResponse patientDiscoverySearchResponse = null;
        String errorText = null;  // Hope for the best.
        try {
            // Get SubjectSearchCriteria instance from PIX Query.
            SubjectSearchCriteria subjectSearchCriteria = this.getSubjectSearchCriteria(request);

            // Validate that PIX Query has what is required.
            this.validateSearchCriteriaPIXRequest(subjectSearchCriteria);

            // Now issue a PDQ to PDS for the supplied patient id (on PIX query).
            DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
            SubjectSearchResponse pdqSearchResponse = this.findCandidatesQuery(senderDeviceInfo, subjectSearchCriteria);

            // See if we only have one match (from PDS) for the patient.
            List<Subject> subjects = pdqSearchResponse.getSubjects();
            if (subjects.size() == 0) {
                errorText = "0 local subjects found for PIX query request";
            } else if (subjects.size() > 1) {
                // Should not be feasible, but check anyway.
                errorText = "> 1 local subjects found for PIX query request";
            } else {
                // Get the first subject from the list.
                Subject subject = subjects.get(0);

                // Get ready to send CGPD request.
                SubjectSearchCriteria patientDiscoverySearchCriteria = new SubjectSearchCriteria();
                patientDiscoverySearchCriteria.setSubject(subject);
                patientDiscoverySearchCriteria.setMinimumDegreeMatchPercentage(subjectSearchCriteria.getMinimumDegreeMatchPercentage());

                // FIXME: Should strip out all ids except for the one that matches the
                // communityAssigningAuthority
                SubjectIdentifierDomain communityAssigningAuthority = subjectSearchCriteria.getCommunityAssigningAuthority();
                patientDiscoverySearchCriteria.setCommunityAssigningAuthority(communityAssigningAuthority);

                // Fan out CGPD requests and get collective response.
                patientDiscoverySearchResponse = this.performCrossGatewayPatientDiscovery(patientDiscoverySearchCriteria);
            }
        } catch (XCPDException ex) {
            errorText = ex.getMessage();
        }
        result = this.getCrossGatewayPatientDiscoveryResponse(request, patientDiscoverySearchResponse, errorText);
        this.log(errorText);
        this.validateHL7V3Message(result);
        return result;
    }

    /**
     *
     * @param request
     * @return
     * @throws AxisFault
     */
    private MCCI_IN000002UV01_Message processPatientRegistryRecordAdded(PRPA_IN201301UV02_Message request) throws AxisFault {
        this.validateHL7V3Message(request);
        String errorText = null;  // Hope for the best.
        try {
            // Get SubjectSearchCriteria instance from PID Feed.
            SubjectSearchCriteria subjectSearchCriteria = this.getSubjectSearchCriteria(request);
            this.validateSearchCriteriaRequest(subjectSearchCriteria);

            // TBD: Should we see if we know about this patient first?
            // DeviceInfo senderDeviceInfo = this.getDeviceInfo();
            // SubjectSearchResponse pdqSearchResponse = this.findCandidatesQuery(senderDeviceInfo, subjectSearchCriteria);

            // Assume that PID Feed request has valid patient id and demographics (skip PDQ validation).
            SubjectSearchResponse patientDiscoverySearchResponse = this.performCrossGatewayPatientDiscovery(subjectSearchCriteria);

            // Nothing more to do here ...

        } catch (XCPDException ex) {
            errorText = ex.getMessage();
        }
        MCCI_IN000002UV01_Message ackResponse = this.getPatientRegistryRecordAddedResponse(request, errorText);
        this.log(errorText);
        this.validateHL7V3Message(ackResponse);
        return ackResponse;
    }

    /**
     * Convert PDQ query to SubjectSearchCriteria.
     *
     * @param request PDQ query.
     * @return SubjectSearchCriteria
     * @throws XCPDException
     */
    private SubjectSearchCriteria getSubjectSearchCriteria(PRPA_IN201305UV02_Message request) throws XCPDException {
        try {
            SubjectSearchCriteriaBuilder subjectSearchCriteriaBuilder = new SubjectSearchCriteriaBuilder();
            SubjectSearchCriteria subjectSearchCriteria = subjectSearchCriteriaBuilder.buildSubjectSearchCriteria(request);
            SubjectIdentifierDomain communityAssigningAuthority = this.getCommunityAssigningAuthority();
            subjectSearchCriteria.setCommunityAssigningAuthority(communityAssigningAuthority);
            this.setMinimumDegreeMatchPercentage(subjectSearchCriteria);
            return subjectSearchCriteria;
        } catch (ModelBuilderException ex) {
            throw new XCPDException(ex.getMessage());
        }
    }

    /**
     * Convert PIX query to SubjectSearchCriteria.
     *
     * @param request PIX query.
     * @return SubjectSearchCriteria
     * @throws XCPDException
     */
    private SubjectSearchCriteria getSubjectSearchCriteria(PRPA_IN201309UV02_Message request) throws XCPDException {
        try {
            SubjectSearchCriteriaBuilder subjectSearchCriteriaBuilder = new SubjectSearchCriteriaBuilder();
            SubjectSearchCriteria subjectSearchCriteria = subjectSearchCriteriaBuilder.buildSubjectSearchCriteria(request);
            SubjectIdentifierDomain communityAssigningAuthority = this.getCommunityAssigningAuthority();
            subjectSearchCriteria.setCommunityAssigningAuthority(communityAssigningAuthority);
            subjectSearchCriteria.addScopingAssigningAuthority(communityAssigningAuthority);
            this.setMinimumDegreeMatchPercentage(subjectSearchCriteria);
            return subjectSearchCriteria;
        } catch (ModelBuilderException ex) {
            throw new XCPDException(ex.getMessage());
        }
    }

    /**
     * Convert PID Feed (Add) to SubjectSearchCriteria.
     *
     * @param request PID Feed (Add).
     * @return SubjectSearchCriteria
     * @throws XCPDException
     */
    private SubjectSearchCriteria getSubjectSearchCriteria(PRPA_IN201301UV02_Message request) throws XCPDException {
        try {
            SubjectBuilder builder = new SubjectBuilder();
            Subject searchSubject = builder.buildSubject(request);
            SubjectSearchCriteria subjectSearchCriteria = new SubjectSearchCriteria();
            subjectSearchCriteria.setSubject(searchSubject);
            SubjectIdentifierDomain communityAssigningAuthority = this.getCommunityAssigningAuthority();
            subjectSearchCriteria.setCommunityAssigningAuthority(communityAssigningAuthority);
            //subjectSearchCriteria.addScopingAssigningAuthority(communityAssigningAuthority);
            this.setMinimumDegreeMatchPercentage(subjectSearchCriteria);
            return subjectSearchCriteria;
        } catch (ModelBuilderException ex) {
            throw new XCPDException(ex.getMessage());
        }
    }

    /**
     *
     * @param subjectSearchCriteria
     */
    private void setMinimumDegreeMatchPercentage(SubjectSearchCriteria subjectSearchCriteria) {
        if (subjectSearchCriteria.hasSpecifiedMinimumDegreeMatchPercentage() == false) {
            logger.info("Setting MinimumDegreeMatchPercentage from xconfig");
            String minimumDegreeMatchPercentageText = this.getGatewayConfigProperty("MinimumDegreeMatchPercentage");
            int minimumDegreeMatchPercentage = XCPDInitiatingGatewayRequestHandler.DEFAULT_MINIMUM_DEGREE_MATCH_PERCENTAGE;
            if (minimumDegreeMatchPercentageText != null) {
                minimumDegreeMatchPercentage = new Integer(minimumDegreeMatchPercentageText);
            }
            subjectSearchCriteria.setSpecifiedMinimumDegreeMatchPercentage(true);
            subjectSearchCriteria.setMinimumDegreeMatchPercentage(new Integer(minimumDegreeMatchPercentage));
        }
    }

    /**
     *
     * @param subjectSearchCriteria
     * @throws XCPDException
     */
    private void validateSearchCriteriaRequest(SubjectSearchCriteria subjectSearchCriteria) throws XCPDException {
        SubjectIdentifierDomain communityAssigningAuthority = subjectSearchCriteria.getCommunityAssigningAuthority();

        // Validate request:
        Subject subject = subjectSearchCriteria.getSubject();

        // Make sure that at least one subject identifier is for the
        // designated "CommunityAssigningAuthority"
        boolean foundCommunityAssigningAuthority = false;
        for (SubjectIdentifier subjectIdentifier : subject.getSubjectIdentifiers()) {
            SubjectIdentifierDomain subjectIdentifierDomain = subjectIdentifier.getIdentifierDomain();
            if (subjectIdentifierDomain.getUniversalId().equals(communityAssigningAuthority.getUniversalId())) {
                foundCommunityAssigningAuthority = true;
                break;
            }
        }

        if (foundCommunityAssigningAuthority == false) {
            // Did not find an appropriate patient on the request.
            throw new XCPDException(
                    "You must specify at least one LivingSubjectId for the " +
                    communityAssigningAuthority.getUniversalId() + " assigning authority");
        }

        // Check required fields (Subject + BirthTime).
        if (subject.getSubjectNames().size() == 0) {
            throw new XCPDException("LivingSubjectName required");
        }

        if (subject.getBirthTime() == null) {
            throw new XCPDException("LivingSubjectBirthTime required");
        }
    }

    /**
     *
     * @param subjectSearchCriteria
     * @throws XCPDException
     */
    private void validateSearchCriteriaPIXRequest(SubjectSearchCriteria subjectSearchCriteria) throws XCPDException {
        SubjectIdentifierDomain communityAssigningAuthority = subjectSearchCriteria.getCommunityAssigningAuthority();

        // Validate request:
        Subject subject = subjectSearchCriteria.getSubject();

        // Make sure we have an identifier.
        List<SubjectIdentifier> subjectIdentifiers = subject.getSubjectIdentifiers();
        if (subjectIdentifiers == null) {
            throw new XCPDException(
                    "You must specify one LivingSubjectId for the " +
                    communityAssigningAuthority.getUniversalId() + " assigning authority");
        }

        // Make sure we only have one identifier.
        if (subjectIdentifiers.size() > 1) {
            throw new XCPDException(
                    "You must specify only one LivingSubjectId for the " +
                    communityAssigningAuthority.getUniversalId() + " assigning authority");
        }

        // Make sure that the specified subject identifier is for the
        // designated "CommunityAssigningAuthority"
        SubjectIdentifier subjectIdentifier = subject.getSubjectIdentifiers().get(0);
        SubjectIdentifierDomain subjectIdentifierDomain = subjectIdentifier.getIdentifierDomain();
        if (!subjectIdentifierDomain.getUniversalId().equals(communityAssigningAuthority.getUniversalId())) {
            // Did not find an appropriate identifier on the request.
            throw new XCPDException(
                    "You must specify one LivingSubjectId for the " +
                    communityAssigningAuthority.getUniversalId() + " assigning authority");
        }
    }

    /**
     * 
     * @param patientDiscoverySearchCriteria
     * @return
     */
    private SubjectSearchResponse performCrossGatewayPatientDiscovery(SubjectSearchCriteria patientDiscoverySearchCriteria) {
        // Prepare gateway requests.
        List<GatewayRequest> gatewayRequests = this.getGatewayRequests(patientDiscoverySearchCriteria);

        // Issue XCPD CrossGatewayPatientDiscovery requests to targeted gateways (in parallel).
        List<GatewayResponse> gatewayResponses = this.sendGatewayRequests(gatewayRequests);

        // Process responses.
        return this.processResponses(gatewayResponses, patientDiscoverySearchCriteria);
    }

    /**
     *
     * @param PRPA_IN201301UV02_Message
     * @param errorText
     * @return
     */
    private MCCI_IN000002UV01_Message getPatientRegistryRecordAddedResponse(PRPA_IN201301UV02_Message request, String errorText) {
        DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
        DeviceInfo receiverDeviceInfo = HL7V3MessageBuilderHelper.getSenderDeviceInfo(request);
        MCCI_IN000002UV01_Message_Builder ackBuilder = new MCCI_IN000002UV01_Message_Builder(senderDeviceInfo, receiverDeviceInfo);
        MCCI_IN000002UV01_Message ackResponse = ackBuilder.buildMCCI_IN000002UV01(request, errorText);
        return ackResponse;
    }

    /**
     *
     * @param request
     * @param subjectSearchResponse
     * @param errorText
     * @return
     */
    private PRPA_IN201306UV02_Message getPatientRegistryFindCandidatesQueryResponse(PRPA_IN201305UV02_Message request,
            SubjectSearchResponse subjectSearchResponse, String errorText) {
        DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
        DeviceInfo receiverDeviceInfo = HL7V3MessageBuilderHelper.getSenderDeviceInfo(request);
        PRPA_IN201306UV02_Message_Builder builder =
                new PRPA_IN201306UV02_Message_Builder(senderDeviceInfo, receiverDeviceInfo);
        return builder.buildPRPA_IN201306UV02_Message(request, subjectSearchResponse, errorText);
    }

    /**
     * 
     * @param request
     * @param subjectSearchResponse
     * @param errorText
     * @return
     */
    private PRPA_IN201310UV02_Message getCrossGatewayPatientDiscoveryResponse(PRPA_IN201309UV02_Message request,
            SubjectSearchResponse subjectSearchResponse, String errorText) {
        DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
        DeviceInfo receiverDeviceInfo = HL7V3MessageBuilderHelper.getSenderDeviceInfo(request);
        PRPA_IN201310UV02_Message_Builder builder = new PRPA_IN201310UV02_Message_Builder(senderDeviceInfo, receiverDeviceInfo);
        return builder.buildPRPA_IN201310UV02_Message(request, subjectSearchResponse, errorText);
    }

    /**
     *
     * @param patientDiscoverySearchCriteria
     * @return
     */
    private List<GatewayRequest> getGatewayRequests(SubjectSearchCriteria patientDiscoverySearchCriteria) {
        List<GatewayRequest> requests = new ArrayList<GatewayRequest>();
        // First get list of target XCPD Responding Gateways.
        List<XConfigActor> rgConfigs = this.getXCPDRespondingGateways(patientDiscoverySearchCriteria);

        // Now prepare gateway requests.
        for (XConfigActor rgConfig : rgConfigs) {
            // Prepare Gateway request.
            GatewayRequest gatewayRequest = new GatewayRequest();
            gatewayRequest.setRGConfig(rgConfig);
            gatewayRequest.setSubjectSearchCriteria(patientDiscoverySearchCriteria);

            DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
            DeviceInfo receiverDeviceInfo = this.getDeviceInfo(rgConfig);
            // See if the target RespondingGateway can handle receipt of our community's
            // patientid as a LivingSubjectId parameter.
            // boolean sendCommunityPatientId = rgConfig.getPropertyAsBoolean("SendCommunityPatientId", true);

            PRPA_IN201305UV02_Message_Builder builder =
                    new PRPA_IN201305UV02_Message_Builder(senderDeviceInfo, receiverDeviceInfo);
            PRPA_IN201305UV02_Message cgpdRequest = builder.buildPRPA_IN201305UV02_Message(patientDiscoverySearchCriteria);

            gatewayRequest.setRequest(cgpdRequest);
            requests.add(gatewayRequest);
        }
        return requests;
    }

    /**
     *
     * @param patientDiscoverySearchCriteria
     * @return
     */
    private List<XConfigActor> getXCPDRespondingGateways(SubjectSearchCriteria patientDiscoverySearchCriteria) {
        List<XConfigActor> targetGateways = new ArrayList<XConfigActor>();

        // First get list of possible target XCPD Responding Gateways.
        List<XConfigActor> possibleTargetGateways = this.getXCPDRespondingGateways();

        // Get subject identifier for the search subject (as it is known in this community).
        SubjectIdentifier communitySubjectIdentifier = this.getSubjectIdentifier(patientDiscoverySearchCriteria);

        try {
            // Now consult the cache.
            PatientCorrelationCacheService cacheService = new PatientCorrelationCacheService();
            String localPatientId = communitySubjectIdentifier.getCXFormatted();
            String localHomeCommunityId = this.getGatewayConfig().getUniqueId();
            List<PatientCorrelationCacheEntry> cacheEntries = cacheService.lookup(localPatientId, localHomeCommunityId);
            for (XConfigActor possibleTargetGateway : possibleTargetGateways) {
                boolean foundInCache = false;
                for (PatientCorrelationCacheEntry cacheEntry : cacheEntries) {
                    if (possibleTargetGateway.getUniqueId().equalsIgnoreCase(cacheEntry.getRemoteHomeCommunityId())) {
                        foundInCache = true;
                        break;
                    }
                }
                if (!foundInCache) {
                    targetGateways.add(possibleTargetGateway);
                }
            }

        } catch (Exception ex) {
            log_message.setPass(false);
            if (log_message.isLogEnabled()) {
                log_message.addErrorParam("XCPD EXCEPTION", ex.getMessage());
            }
        }

        return targetGateways;
    }

    /**
     *
     * @param requests
     * @return
     */
    private List<GatewayResponse> sendGatewayRequests(List<GatewayRequest> requests) {
        ArrayList<GatewayResponse> responses = new ArrayList<GatewayResponse>();

        boolean XCPDMultiThread = true;  // FIXME: Place into XConfig.
        boolean multiThreadMode = false;
        ArrayList<Future<GatewayResponse>> futures = null;
        int taskSize = requests.size();
        if (XCPDMultiThread == true && taskSize > 1) {  // FIXME: Task bound size should be configurable.
            // Do multi-threading.
            multiThreadMode = true;
            futures = new ArrayList<Future<GatewayResponse>>();
        }
        logger.debug("*** multiThreadMode = " + multiThreadMode + " ***");

        // Submit work to be conducted in parallel (if required):
        for (GatewayRequest request : requests) {
            // Each pass is for a single entity (Responding Gateway).
            GatewayCallable callable = new GatewayCallable(request);
            if (multiThreadMode == true) {
                Future<GatewayResponse> future = this.submit(callable);
                futures.add(future);
            } else {
                // Not in multi-thread mode.
                // Just call in the current thread.
                try {
                    GatewayResponse gatewayResponse = callable.call();
                    if (gatewayResponse != null) {
                        responses.add(gatewayResponse);
                    }
                } catch (Exception ex) {
                    // Ignore here, already logged.
                    // Do not remove this exception logic!!!!!!!!
                    // Should never happen, but don't want to stop progress ...
                    //logger.error("XCPD EXCEPTION ... continuing", ex);
                }
            }
        }

        // If in mult-thread mode, wait for futures ...
        if (multiThreadMode == true) {
            for (Future<GatewayResponse> future : futures) {
                try {
                    GatewayResponse gatewayResponse = future.get();  // Will block until ready.
                    responses.add(gatewayResponse);
                    //logger.debug("*** FINISHED THREAD - " + requestCollection.getUniqueId());
                    //this.processOutboundRequestResult(requestCollection, results);
                } catch (InterruptedException ex) {
                    logger.error("XCPD EXCEPTION ... continuing", ex);
                } catch (ExecutionException ex) {
                    logger.error("XCPD EXCEPTION ... continuing", ex);
                }
            }
        }
        return responses;
    }

    /**
     * 
     * @param responses
     * @param patientDiscoverySearchCriteria
     * @return
     */
    private SubjectSearchResponse processResponses(List<GatewayResponse> responses, SubjectSearchCriteria patientDiscoverySearchCriteria) {
        // Get ready to aggregate all responses.
        SubjectSearchResponse aggregatedSubjectSearchResponse = new SubjectSearchResponse();
        List<Subject> aggregatedSubjects = aggregatedSubjectSearchResponse.getSubjects();

        // Get subject identifier for the search subject (as it is known in this community).
        SubjectIdentifier communitySubjectIdentifier = this.getSubjectIdentifier(patientDiscoverySearchCriteria);

        // FIXME: May want to do all of this in parallel ...

        // Go through each response.
        for (GatewayResponse gatewayResponse : responses) {
            List<Subject> subjects = this.processResponse(gatewayResponse, communitySubjectIdentifier);
            if (subjects.size() > 0) {
                aggregatedSubjects.addAll(subjects);
            }
            // Always update the cache ... we will keep track of non-responses also so we don't
            // search again until the cache is flushed for the remote community.
            this.updatePatientCorrelationCache(gatewayResponse, communitySubjectIdentifier, subjects);
        }
        return aggregatedSubjectSearchResponse;
    }

    /**
     *
     * @param gatewayResponse
     * @param communitySubjectIdentifier
     * @return
     */
    private List<Subject> processResponse(GatewayResponse gatewayResponse, SubjectIdentifier communitySubjectIdentifier) {
        List<Subject> resultSubjects = new ArrayList<Subject>();
        List<Subject> noMatchSubjects = new ArrayList<Subject>();
        SubjectSearchResponse subjectSearchResponse = this.getSubjectSearchResponse(gatewayResponse);

        // Now validate that each remote subject matches our local subject.
        for (Subject remoteSubject : subjectSearchResponse.getSubjects()) {

            // Save (then clear) remote SubjectIdentifiers (we don't want them in our local PDQ query).
            List<SubjectIdentifier> remoteSubjectIdentifiers = remoteSubject.getSubjectIdentifiers();
            remoteSubject.setSubjectIdentifiers(new ArrayList<SubjectIdentifier>());

            // Prepare PDQ search criteria.
            SubjectSearchCriteria pdqSubjectSearchCriteria = new SubjectSearchCriteria();
            pdqSubjectSearchCriteria.setSubject(remoteSubject);

            // FIXME: ? Not sure if necessary ?
            this.setMinimumDegreeMatchPercentage(pdqSubjectSearchCriteria);

            // Scope PDQ request to local community assigning authority only.
            SubjectIdentifierDomain communityAssigningAuthority = this.getCommunityAssigningAuthority();
            pdqSubjectSearchCriteria.setCommunityAssigningAuthority(communityAssigningAuthority);
            pdqSubjectSearchCriteria.addScopingAssigningAuthority(communityAssigningAuthority);

            try {
                // Issue PDQ using demographics supplied by remote community.
                DeviceInfo senderDeviceInfo = this.getSenderDeviceInfo();
                SubjectSearchResponse pdqSearchResponse = this.findCandidatesQuery(senderDeviceInfo, pdqSubjectSearchCriteria);

                // Restore identifiers in remote Subject.
                remoteSubject.setSubjectIdentifiers(remoteSubjectIdentifiers);

                // Now see if we can confirm a match.
                // See if we find our subject's identifier in the PDQ response.
                List<Subject> localSubjects = pdqSearchResponse.getSubjects();
                boolean remoteSubjectMatch = false;
                for (Subject localSubject : localSubjects) {
                    if (localSubject.hasSubjectIdentifier(communitySubjectIdentifier)) {
                        remoteSubjectMatch = true;
                        break;  // No need to look further.
                    }
                }
                if (remoteSubjectMatch == true) {
                    // Match!!! ... add the remote subject to aggregated result.
                    resultSubjects.add(remoteSubject);
                } else {
                    // No match ... keep track for logging purposes.
                    noMatchSubjects.add(remoteSubject);
                }
            } catch (AxisFault ex) {
                logger.error("XCPD EXCEPTION ... continuing", ex);
                // Continue loop.
            }
        }
        this.log(gatewayResponse, resultSubjects, noMatchSubjects);
        return resultSubjects;
    }

    /**
     *
     * @param gatewayResponse
     * @param communitySubjectIdentifier
     * @param subjects
     */
    private void updatePatientCorrelationCache(
            GatewayResponse gatewayResponse, SubjectIdentifier communitySubjectIdentifier, List<Subject> subjects) {

        String localPatientId = communitySubjectIdentifier.getCXFormatted();
        String localHomeCommunityId;
        String remoteHomeCommunityId = gatewayResponse.getRequest().getRGConfig().getUniqueId();
        try {
            localHomeCommunityId = this.getGatewayConfig().getUniqueId();
        } catch (Exception ex) {
            log_message.setPass(false);
            if (log_message.isLogEnabled()) {
                log_message.addErrorParam("EXCEPTION: " + gatewayResponse.getRequest().getVitals(), ex.getMessage());
            }
            return;
        }
        List<PatientCorrelationCacheEntry> cacheEntries = new ArrayList<PatientCorrelationCacheEntry>();
        if (subjects.size() == 0) {
            // Just note the fact that we did a search for this patient for the community with
            // no success (so we don't search again until the cache is flushed).
            PatientCorrelationCacheEntry cacheEntry =
                    this.getPatientCorrelationCacheEntry(localPatientId, localHomeCommunityId, null, remoteHomeCommunityId);
            cacheEntry.setStatus(PatientCorrelationCacheEntry.STATUS_NOTFOUND);
            cacheEntries.add(cacheEntry);  // Add to the list.
        } else {
            for (Subject subject : subjects) {
                // FIXME!!!!!
                // FIXME: for now store all patient identifiers from community.
                // FIXME: should only store those for configured assigning authority.
                for (SubjectIdentifier subjectIdentifier : subject.getSubjectIdentifiers()) {
                    String remotePatientId = subjectIdentifier.getCXFormatted();
                    PatientCorrelationCacheEntry cacheEntry =
                            this.getPatientCorrelationCacheEntry(localPatientId, localHomeCommunityId, remotePatientId, remoteHomeCommunityId);
                    cacheEntry.setStatus(PatientCorrelationCacheEntry.STATUS_ACTIVE);

                    // Add to the list to store.
                    cacheEntries.add(cacheEntry);
                }
            }
        }

        // Store patient correlations in cache ...
        try {
            PatientCorrelationCacheService cacheService = new PatientCorrelationCacheService();
            cacheService.store(cacheEntries);
        } catch (PatientCorrelationCacheException ex) {
            log_message.setPass(false);
            if (log_message.isLogEnabled()) {
                log_message.addErrorParam("EXCEPTION: " + gatewayResponse.getRequest().getVitals(), ex.getMessage());
            }
        }
    }

    /**
     *
     * @param localPatientId
     * @param localHomeCommunityId
     * @param remotePatientId
     * @param remoteHomeCommunityId
     * @return
     */
    private PatientCorrelationCacheEntry getPatientCorrelationCacheEntry(
            String localPatientId, String localHomeCommunityId, String remotePatientId, String remoteHomeCommunityId) {
        PatientCorrelationCacheEntry cacheEntry = new PatientCorrelationCacheEntry();
        cacheEntry.setLocalPatientId(localPatientId);
        cacheEntry.setLocalHomeCommunityId(localHomeCommunityId);
        cacheEntry.setRemotePatientId(remotePatientId);
        cacheEntry.setRemoteHomeCommunityId(remoteHomeCommunityId);
        return cacheEntry;
    }

    /**
     * 
     * @param gatewayResponse
     * @param matches
     * @param noMatches
     */
    private void log(GatewayResponse gatewayResponse, List<Subject> matches, List<Subject> noMatches) {
        if (matches.size() > 0) {
            if (log_message.isLogEnabled()) {
                String logText = this.getLogText(matches);
                log_message.addOtherParam("CGPD RESPONSE " + gatewayResponse.request.getVitals(),
                        "CONFIRMED MATCH (count=" + matches.size() + "): " + logText);
            }
        }
        if (noMatches.size() > 0) {
            log_message.setPass(false);
            if (log_message.isLogEnabled()) {
                String logText = this.getLogText(noMatches);
                log_message.addErrorParam("CGPD RESPONSE " + gatewayResponse.request.getVitals(),
                        "NO CONFIRMED MATCH (count=" + noMatches.size() + "): " + logText);
            }
        }
    }

    /**
     *
     * @param subjects
     * @return
     */
    private String getLogText(List<Subject> subjects) {
        String logText = new String();
        int count = 0;
        for (Subject subject : subjects) {
            for (SubjectIdentifier subjectIdentifier : subject.getSubjectIdentifiers()) {
                if (count > 0) {
                    logText = logText + ", ";
                }
                ++count;
                logText = logText + subjectIdentifier.getCXFormatted();
            }
        }
        return logText;
    }

    /**
     * Return SubjectIdentifier in the CommunityAssigningAuthority using the Subject search
     * template in the given SubjectSearchCriteria.  Return null if not found (should not be
     * the case here based on prior validations).
     *
     * @param patientDiscoverySearchCriteria
     * @return
     */
    private SubjectIdentifier getSubjectIdentifier(SubjectSearchCriteria patientDiscoverySearchCriteria) {
        SubjectIdentifierDomain communityAssigningAuthority = this.getCommunityAssigningAuthority();
        return patientDiscoverySearchCriteria.getSubjectIdentifier(communityAssigningAuthority);
    }

    /**
     * 
     * @param gatewayResponse
     */
    private SubjectSearchResponse getSubjectSearchResponse(GatewayResponse gatewayResponse) {
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();
        PRPA_IN201306UV02_Message cgpdResponse = gatewayResponse.getResponse();
        try {
            this.validateHL7V3Message(cgpdResponse);
        } catch (AxisFault ex) {
            log_message.setPass(false);
            if (log_message.isLogEnabled()) {
                log_message.addErrorParam("EXCEPTION: " + gatewayResponse.getRequest().getVitals(), ex.getMessage());
            }
            logger.error("CGPD Response did not validate against XML schema: " + ex.getMessage());
            return subjectSearchResponse;
        }
        try {
            SubjectBuilder subjectBuilder = new SubjectBuilder();
            subjectSearchResponse = subjectBuilder.buildSubjectSearchResponse(cgpdResponse);

        } catch (ModelBuilderException ex) {
            // TBD ....
        }
        return subjectSearchResponse;
    }

    /**
     *
     * @param request
     * @return
     */
    // SYNCHRONIZED!
    private synchronized Future<GatewayResponse> submit(GatewayCallable request) {
        ExecutorService exec = this.getExecutor();
        Future<GatewayResponse> future = exec.submit(request);
        return future;
    }

    /**
     *
     * @return
     */
    private ExecutorService getExecutor() {
        if (_executor == null) {
            // Shared across all web service requests.
            _executor = Executors.newCachedThreadPool();
        }
        return _executor;
    }

    /**
     *
     * @return
     * @throws AxisFault
     */
    @Override
    protected synchronized XConfigActor getPDSConfig() throws AxisFault {
        if (_pdsConfig != null) {
            return _pdsConfig;
        }
        XConfigObject gatewayConfig = this.getGatewayConfig();

        // Now get the "PDS" object (and cache it away).
        _pdsConfig = (XConfigActor) gatewayConfig.getXConfigObjectWithName("pds", XConfig.PDS_TYPE);
        return _pdsConfig;
    }

    /**
     * 
     * @return
     * @throws AxisFault
     */
    private synchronized List<XConfigActor> getXCPDRespondingGateways() {
        if (_xcpdRespondingGateways != null) {
            return _xcpdRespondingGateways;
        }
        // Get gateway configuration.
        XConfigObject gatewayConfig;
        try {
            gatewayConfig = this.getGatewayConfig();
        } catch (AxisFault ex) {
            logger.error("XCPD EXCEPTION: " + ex.getMessage());
            return new ArrayList<XConfigActor>();
        }

        // Get the list of XCPD Responding Gateways configuration.
        XConfigObject xcpdConfig = gatewayConfig.getXConfigObjectWithName("xcpd_rgs", "XCPDRespondingGatewaysType");
        _xcpdRespondingGateways = new ArrayList<XConfigActor>();
        if (xcpdConfig == null) {
            logger.warn("No target XCPD Responding Gateways configured.");
        } else {
            // Now navigate through the list of configurations and cache them.
            List<XConfigObject> rgConfigs = xcpdConfig.getXConfigObjectsWithType(XConfig.XCA_RESPONDING_GATEWAY_TYPE);
            if (rgConfigs.size() == 0) {
                logger.warn("No target XCPD Responding Gateways configured.");
            } else {
                for (XConfigObject rgConfig : rgConfigs) {
                    _xcpdRespondingGateways.add((XConfigActor) rgConfig);
                }
            }
        }
        return _xcpdRespondingGateways;
    }

    /**
     * 
     */
    public class GatewayRequest {

        private XConfigActor rgConfig;
        private PRPA_IN201305UV02_Message request;
        SubjectSearchCriteria subjectSearchCriteria;

        public XConfigActor getRGConfig() {
            return rgConfig;
        }

        public String getEndpoint() {
            XConfigTransaction txn = rgConfig.getTransaction("CrossGatewayPatientDiscovery");
            return txn.getEndpointURL();
        }

        public void setRGConfig(XConfigActor rgConfig) {
            this.rgConfig = rgConfig;
        }

        private PRPA_IN201305UV02_Message getRequest() {
            return request;
        }

        private void setRequest(PRPA_IN201305UV02_Message request) {
            this.request = request;
        }

        public SubjectSearchCriteria getSubjectSearchCriteria() {
            return subjectSearchCriteria;
        }

        public void setSubjectSearchCriteria(SubjectSearchCriteria subjectSearchCriteria) {
            this.subjectSearchCriteria = subjectSearchCriteria;
        }

        /**
         *
         * @return
         */
        public String getVitals() {
            return "(community: " + this.getRGConfig().getUniqueId() + ", endpoint: " + this.getEndpoint() + ")";
        }
    }

    /**
     * 
     */
    public class GatewayResponse {

        private PRPA_IN201306UV02_Message response;
        private GatewayRequest request;

        private PRPA_IN201306UV02_Message getResponse() {
            return response;
        }

        private void setResponse(PRPA_IN201306UV02_Message response) {
            this.response = response;
        }

        private void setRequest(GatewayRequest request) {
            this.request = request;
        }

        private GatewayRequest getRequest() {
            return this.request;
        }
    }

    /**
     *
     */
    public class GatewayCallable implements Callable<GatewayResponse> {

        private GatewayRequest request;

        /**
         *
         * @param request
         */
        public GatewayCallable(GatewayRequest request) {
            this.request = request;
        }

        /**
         *
         * @return
         * @throws Exception
         */
        public GatewayResponse call() throws Exception {
            PRPA_IN201305UV02_Message message = this.request.getRequest();
            if (log_message.isLogEnabled()) {
                log_message.addOtherParam("CGPD REQUEST " + this.request.getVitals(),
                        message.getMessageNode().toString());
            }

            // Audit
            performATNAAudit(this.request.getRequest(),
                    this.request.getSubjectSearchCriteria(),
                    this.request.getEndpoint());


            // Make the call.
            XCPDGatewayClient client = new XCPDGatewayClient(request.getRGConfig());
            GatewayResponse gatewayResponse = null;
            PRPA_IN201306UV02_Message queryResponse;
            try {
                queryResponse = client.findCandidatesQuery(message);
                if (log_message.isLogEnabled()) {
                    if (queryResponse.getMessageNode() != null) {
                        log_message.addOtherParam("CGPD RESPONSE " + request.getVitals(),
                                queryResponse.getMessageNode().toString());
                    } else {
                        log_message.addErrorParam("CGPD RESPONSE " + request.getVitals(),
                                "NO RESPONSE FROM COMMUNITY");
                    }
                }
                gatewayResponse = new GatewayResponse();
                gatewayResponse.setRequest(this.request);
                gatewayResponse.setResponse(queryResponse);
            } catch (Exception ex) {
                logger.error("XCPD EXCEPTION ... continuing " + request.getVitals(), ex);
                log_message.addErrorParam("EXCEPTION " + request.getVitals(), ex.getMessage());
                // ***** Rethrow is needed otherwise Axis2 gets confused with Async.
                throw ex;  // Rethrow.
            }
            return gatewayResponse;
        }
    }
}
