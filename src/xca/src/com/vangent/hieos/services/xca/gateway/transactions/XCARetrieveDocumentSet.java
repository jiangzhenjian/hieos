/*
 * This code is subject to the HIEOS License, Version 1.0
 *
 * Copyright(c) 2008-2009 Vangent, Inc.  All rights reserved.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vangent.hieos.services.xca.gateway.transactions;

import com.vangent.hieos.xutil.metadata.structure.MetadataTypes;
import com.vangent.hieos.xutil.metadata.structure.MetadataSupport;
import com.vangent.hieos.xutil.registry.RegistryUtility;
import com.vangent.hieos.xutil.response.RetrieveMultipleResponse;
import com.vangent.hieos.xutil.xlog.client.XLogMessage;

// Exceptions.
import com.vangent.hieos.xutil.exception.SchemaValidationException;
import com.vangent.hieos.xutil.exception.XdsInternalException;

import com.vangent.hieos.services.xca.gateway.controller.XCARequestController;
import com.vangent.hieos.services.xca.gateway.controller.XCAAbstractRequestCollection;
import com.vangent.hieos.services.xca.gateway.controller.XCARetrieveRequestCollection;
import com.vangent.hieos.services.xca.gateway.controller.XCARequest;

// XConfig.
import com.vangent.hieos.xutil.xconfig.XConfig;
import com.vangent.hieos.xutil.xconfig.XConfigActor;

// Third party.
import org.apache.axis2.context.MessageContext;
import java.util.ArrayList;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNamespace;
import org.apache.log4j.Logger;

/**
 *
 * @author Bernie Thuman
 */
public abstract class XCARetrieveDocumentSet extends XCAAbstractTransaction {

    private final static Logger logger = Logger.getLogger(XCARetrieveDocumentSet.class);

    /**
     *
     * @param gatewayConfig
     * @param log_message
     * @param messageContext
     */
    public XCARetrieveDocumentSet(XConfigActor gatewayConfig, XLogMessage log_message, MessageContext messageContext) {
        try {
            super.init(gatewayConfig, log_message, new RetrieveMultipleResponse(), messageContext);
        } catch (XdsInternalException e) {
            logger.fatal(logger_exception_details(e));
            response.add_error(MetadataSupport.XDSRepositoryError,
                    e.getMessage(),
                    this.getLocalHomeCommunityId(), log_message);
        }
    }

    /**
     * Make sure that the xdsb namespace is in order.
     *
     * @param request  The root of the XML request.
     */
    protected void validateRequest(OMElement request) {

        // Validate namespace.
        OMNamespace ns = request.getNamespace();
        String ns_uri = ns.getNamespaceURI();
        if (ns_uri == null || !ns_uri.equals(MetadataSupport.xdsB.getNamespaceURI())) {
            response.add_error(MetadataSupport.XDSRepositoryError,
                    "Invalid XML namespace on RetrieveDocumentSetRequest: " + ns_uri,
                    this.getLocalHomeCommunityId(), log_message);
        }

        // Validate against schema.
        try {
            RegistryUtility.schema_validate_local(request, MetadataTypes.METADATA_TYPE_RET);
        } catch (SchemaValidationException e) {
            response.add_error(MetadataSupport.XDSRepositoryMetadataError,
                    "SchemaValidationException: " + e.getMessage(),
                    this.getLocalHomeCommunityId(), log_message);
        } catch (XdsInternalException e) {
            response.add_error(MetadataSupport.XDSRepositoryMetadataError,
                    "SchemaValidationException: " + e.getMessage(),
                    this.getLocalHomeCommunityId(), log_message);
        }
    }

    /**
     *
     * @param uniqueId
     * @param configActor
     * @param isLocalRequest
     */
    protected void addRequest(OMElement queryRequest, String uniqueId, XConfigActor configActor, boolean isLocalRequest) {
        XCARequestController requestController = this.getRequestController();

        // FIXME: Logic is a bit problematic -- need to find another way.
        XCAAbstractRequestCollection requestCollection = requestController.getRequestCollection(uniqueId);
        if (requestCollection == null) {
            requestCollection = new XCARetrieveRequestCollection(uniqueId, configActor, isLocalRequest);
            requestController.setRequestCollection(requestCollection);
        }
        XCARequest xcaRequest = new XCARequest(queryRequest);
        requestCollection.addRequest(xcaRequest);
    }

    /**
     *
     * @param docRequestNode
     * @return
     * @throws com.vangent.hieos.xutil.exception.XdsInternalException
     */
    protected XConfigActor getRepositoryConfigBasedOnDocRequest(OMElement docRequestNode) throws XdsInternalException {
        XConfigActor repositoryConfig = null;
        OMElement repositoryIdNode = MetadataSupport.firstChildWithLocalName(docRequestNode, "RepositoryUniqueId");
        if (repositoryIdNode == null) {
            response.add_error(MetadataSupport.XDSUnknownRepositoryId,
                    "RepositoryUniqueId missing or empty",
                    this.getLocalHomeCommunityId(), log_message);
        }
        String repositoryUniqueId = repositoryIdNode.getText();
        if (repositoryUniqueId == null || repositoryUniqueId.equals("")) {
            response.add_error(MetadataSupport.XDSUnknownRepositoryId,
                    "RepositoryUniqueId missing or empty",
                    this.getLocalHomeCommunityId(), log_message);
        } else {
            // Now see if we know anyting about this repository id within our local community.
            XConfig xconf = XConfig.getInstance();
            repositoryConfig = xconf.getRepositoryConfigById(repositoryUniqueId);
            if (repositoryConfig == null) {
                response.add_error(MetadataSupport.XDSUnknownRepositoryId,
                        "RepositoryUniqueId " + repositoryUniqueId + " not known by local community",
                        this.getLocalHomeCommunityId(), log_message);
            }
        }
        return repositoryConfig;
    }

    /**
     *
     * @param allResponses
     * @throws com.vangent.hieos.xutil.exception.XdsInternalException
     */
    protected boolean consolidateResponses(ArrayList<OMElement> allResponses) throws XdsInternalException {
        boolean atLeastOneSuccess = false;

        // FIXME: Should we Util.deep_copy() here?
        OMElement rootResponseNode = response.getRoot();  // e.g. <RetrieveDocumentSetResponse>
        for (OMElement responseNode : allResponses) {
            // Add responses to the root node
            ArrayList<OMElement> responses = MetadataSupport.decendentsWithLocalName(responseNode, "DocumentResponse");
            for (OMElement subResponseNode : responses) {
                rootResponseNode.addChild(subResponseNode);
            }

            // See if the registry response has a success status.
            OMElement registryResponse = MetadataSupport.firstChildWithLocalName(responseNode, "RegistryResponse");
            String status = registryResponse.getAttributeValue(MetadataSupport.status_qname);
            this.logInfo("Note", "*** Response Status = " + status + " ***");
            if (status.endsWith("Success")) {
                atLeastOneSuccess = true;
            }

            // Consolidate all registry errors into the consolidated error list.
            ArrayList<OMElement> registryErrorLists = MetadataSupport.decendentsWithLocalName(responseNode, "RegistryErrorList");

            // Should only be one <RegistryErrorList> at most, but loop anyway.
            for (OMElement registryErrorList : registryErrorLists) {
                response.addRegistryErrorList(registryErrorList, null);  // Place into the final list.
            }
        }
        return atLeastOneSuccess;
    }
}
