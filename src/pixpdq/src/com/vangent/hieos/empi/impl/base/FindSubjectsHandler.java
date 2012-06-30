/*
 * This code is subject to the HIEOS License, Version 1.0
 *
 * Copyright(c) 2011 Vangent, Inc.  All rights reserved.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vangent.hieos.empi.impl.base;

import com.vangent.hieos.empi.exception.EMPIException;
import com.vangent.hieos.empi.match.MatchAlgorithm;
import com.vangent.hieos.empi.match.MatchAlgorithm.MatchType;
import com.vangent.hieos.empi.match.MatchResults;
import com.vangent.hieos.empi.match.Record;
import com.vangent.hieos.empi.match.RecordBuilder;
import com.vangent.hieos.empi.match.ScoredRecord;
import com.vangent.hieos.empi.persistence.PersistenceManager;
import com.vangent.hieos.empi.validator.FindSubjectsValidator;
import com.vangent.hieos.hl7v3util.model.subject.DeviceInfo;
import com.vangent.hieos.hl7v3util.model.subject.InternalId;
import com.vangent.hieos.hl7v3util.model.subject.Subject;
import com.vangent.hieos.hl7v3util.model.subject.SubjectIdentifier;
import com.vangent.hieos.hl7v3util.model.subject.SubjectIdentifierDomain;
import com.vangent.hieos.hl7v3util.model.subject.SubjectSearchCriteria;
import com.vangent.hieos.hl7v3util.model.subject.SubjectSearchResponse;
import com.vangent.hieos.xutil.xconfig.XConfigActor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;

/**
 *
 * @author Bernie Thuman
 */
public class FindSubjectsHandler extends BaseHandler {

    private static final Logger logger = Logger.getLogger(FindSubjectsHandler.class);

    /**
     *
     * @param configActor
     * @param persistenceManager
     * @param senderDeviceInfo
     */
    public FindSubjectsHandler(XConfigActor configActor, PersistenceManager persistenceManager, DeviceInfo senderDeviceInfo) {
        super(configActor, persistenceManager, senderDeviceInfo);
    }

    /**
     *
     * @param subjectSearchCriteria
     * @return
     * @throws EMPIException
     */
    public SubjectSearchResponse findSubjects(SubjectSearchCriteria subjectSearchCriteria) throws EMPIException {
        PersistenceManager pm = this.getPersistenceManager();

        // First, run validations on input.
        FindSubjectsValidator validator = new FindSubjectsValidator(pm, this.getSenderDeviceInfo());
        validator.validate(subjectSearchCriteria);

        // Create default response.
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();

        // FIXME: This is not entirely accurate, how about "other ids"?
        // Determine which path to take.
        if (subjectSearchCriteria.hasSubjectIdentifiers()) {
            logger.trace("Searching based on identifiers ...");
            subjectSearchResponse = this.loadSubjectByIdentifier(subjectSearchCriteria);
        } else if (subjectSearchCriteria.hasSubjectDemographics()) {
            logger.trace("Searching based on demographics ...");
            subjectSearchResponse = this.loadSubjectMatches(subjectSearchCriteria);
        } else {
            // Do nothing ...
            logger.trace("Not searching at all!!");
        }
        return subjectSearchResponse;
    }

    /**
     *
     * @param subjectSearchCriteria
     * @return
     * @throws EMPIException
     */
    public SubjectSearchResponse findSubjectByIdentifier(SubjectSearchCriteria subjectSearchCriteria) throws EMPIException {
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();

        // First, run validations on input.
        FindSubjectsValidator validator = new FindSubjectsValidator(this.getPersistenceManager(),
                this.getSenderDeviceInfo());
        validator.validate(subjectSearchCriteria);

        // Now, find subjects using the identifier in the search criteria.
        subjectSearchResponse = this.loadIdentifiersForSubjectByIdentifier(subjectSearchCriteria);
        return subjectSearchResponse;
    }

    /**
     *
     * @param subjectSearchCriteria
     * @return
     * @throws EMPIException
     */
    private SubjectSearchResponse loadSubjectMatches(SubjectSearchCriteria subjectSearchCriteria) throws EMPIException {
        PersistenceManager pm = this.getPersistenceManager();
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();
        Subject searchSubject = subjectSearchCriteria.getSubject();
        boolean hasSpecifiedMinimumDegreeMatchPercentage = subjectSearchCriteria.hasSpecifiedMinimumDegreeMatchPercentage();
        int minimumDegreeMatchPercentage = subjectSearchCriteria.getMinimumDegreeMatchPercentage();

        // Run the matching algorithm (in "find" mode).
        RecordBuilder rb = new RecordBuilder();
        Record searchRecord = rb.build(searchSubject);
        MatchAlgorithm matchAlgo = MatchAlgorithm.getMatchAlgorithm(pm);
        MatchResults matchResults = matchAlgo.findMatches(searchRecord, MatchType.SUBJECT_FIND);
        List<ScoredRecord> recordMatches = matchResults.getMatches();

        // Now load subjects from the match results.
        List<Subject> subjectMatches = new ArrayList<Subject>();
        long startTime = System.currentTimeMillis();
        Set<Long> enterpriseSubjectIds = new HashSet<Long>();
        for (ScoredRecord scoredRecord : recordMatches) {
            Record record = scoredRecord.getRecord();
            int matchConfidencePercentage = scoredRecord.getMatchScorePercentage();
            if (logger.isTraceEnabled()) {
                logger.trace("match score = " + scoredRecord.getScore());
                logger.trace("gof score = " + scoredRecord.getGoodnessOfFitScore());
                logger.trace("... matchConfidencePercentage (int) = " + matchConfidencePercentage);
            }
            // See if there is a minimum degree match percentage.
            if (!hasSpecifiedMinimumDegreeMatchPercentage
                    || (matchConfidencePercentage >= minimumDegreeMatchPercentage)) {
                InternalId systemSubjectId = record.getInternalId();
                InternalId enterpriseSubjectId = pm.getEnterpriseSubjectId(systemSubjectId);
                // Avoid adding duplicates to the result.
                if (!enterpriseSubjectIds.contains(enterpriseSubjectId.getId())) {
                    enterpriseSubjectIds.add(enterpriseSubjectId.getId());
                    Subject enterpriseSubject = pm.loadEnterpriseSubject(enterpriseSubjectId);
                    enterpriseSubject.setMatchConfidencePercentage(matchConfidencePercentage);

                    // Filter unwanted results (if required).
                    this.filterSubjectIdentifiers(subjectSearchCriteria, enterpriseSubject, null);

                    // FIXME: What about "other ids"?
                    // If we kept at least one identifier ...
                    if (enterpriseSubject.hasSubjectIdentifiers()) {
                        subjectMatches.add(enterpriseSubject);
                    }
                }
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("... not within specified minimum degree match percentage = " + minimumDegreeMatchPercentage);
                }
            }
        }
        long endTime = System.currentTimeMillis();
        if (logger.isTraceEnabled()) {
            logger.trace("FindSubjectsHandler.loadSubjectMatches.loadSubjects: elapedTimeMillis=" + (endTime - startTime));
        }
        subjectSearchResponse.getSubjects().addAll(subjectMatches);
        return subjectSearchResponse;
    }

    /**
     *
     * @param subjectSearchCriteria
     * @return
     * @throws EMPIException
     */
    private SubjectSearchResponse loadSubjectByIdentifier(
            SubjectSearchCriteria subjectSearchCriteria) throws EMPIException {
        PersistenceManager pm = this.getPersistenceManager();
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();

        // Make sure we at least have one identifier to search from.
        Subject searchSubject = subjectSearchCriteria.getSubject();
        List<SubjectIdentifier> searchSubjectIdentifiers = searchSubject.getSubjectIdentifiers();
        if (!searchSubjectIdentifiers.isEmpty()) {
            // Pull the first identifier.
            // FIXME: Need to deal with multiple search identifiers (for PDQ .. not sure about PIX) ....
            SubjectIdentifier searchSubjectIdentifier = searchSubjectIdentifiers.get(0);

            // Get the baseSubject (only base-level information) to determine type and internal id.
            Subject baseSubject = pm.loadBaseSubjectByIdentifier(searchSubjectIdentifier);
            if (baseSubject != null) {  // Found a match.

                // Load enterprise subject (load full instance).
                Subject enterpriseSubject = this.loadEnterpriseSubject(baseSubject, true);

                // Now, strip out identifiers (if required).
                this.filterSubjectIdentifiers(subjectSearchCriteria, enterpriseSubject, null);

                // FIXME(?): What if no identifiers exist?

                // Put enterprise subject in the response.
                enterpriseSubject.setMatchConfidencePercentage(100);
                subjectSearchResponse.getSubjects().add(enterpriseSubject);
            }
        }
        return subjectSearchResponse;
    }

    /**
     * 
     * @param subjectSearchCriteria
     * @return
     * @throws EMPIException
     */
    private SubjectSearchResponse loadIdentifiersForSubjectByIdentifier(
            SubjectSearchCriteria subjectSearchCriteria) throws EMPIException {
        PersistenceManager pm = this.getPersistenceManager();
        SubjectSearchResponse subjectSearchResponse = new SubjectSearchResponse();

        // Make sure we at least have one identifier to search from.
        Subject searchSubject = subjectSearchCriteria.getSubject();
        List<SubjectIdentifier> searchSubjectIdentifiers = searchSubject.getSubjectIdentifiers();
        if (!searchSubjectIdentifiers.isEmpty()) {
            // Pull the first identifier.
            // FIXME: Need to deal with multiple search identifiers (for PDQ .. not sure about PIX) ....
            SubjectIdentifier searchSubjectIdentifier = searchSubjectIdentifiers.get(0);

            // Get the baseSubject (only base-level information) to determine type and internal id.
            Subject baseSubject = pm.loadBaseSubjectByIdentifier(searchSubjectIdentifier);
            if (baseSubject == null) {
                throw new EMPIException(
                        searchSubjectIdentifier.getCXFormatted()
                        + " is not a known identifier",
                        EMPIException.ERROR_CODE_UNKNOWN_KEY_IDENTIFIER);
            }

            // Fall-through.

            // Load enterprise subject (id's only).
            Subject enterpriseSubject = this.loadEnterpriseSubject(baseSubject, false);

            // Now, strip out identifiers (if required).
            this.filterSubjectIdentifiers(subjectSearchCriteria, enterpriseSubject, searchSubjectIdentifier);

            // FIXME: What about "other ids"?
            if (enterpriseSubject.hasSubjectIdentifiers()) {

                // Put enterprise subject in the response.
                enterpriseSubject.setMatchConfidencePercentage(100);
                subjectSearchResponse.getSubjects().add(enterpriseSubject);
            }
        }
        return subjectSearchResponse;
    }

    /**
     *
     * @param baseSubject
     * @param loadFullSubject
     * @return
     * @throws EMPIException
     */
    private Subject loadEnterpriseSubject(Subject baseSubject, boolean loadFullSubject) throws EMPIException {
        PersistenceManager pm = this.getPersistenceManager();
        InternalId enterpriseSubjectId = null;
        if (baseSubject.getType().equals(Subject.SubjectType.ENTERPRISE)) {
            enterpriseSubjectId = baseSubject.getInternalId();
        } else {
            // Get enterpiseSubjectId for the system-level subject.
            enterpriseSubjectId = pm.getEnterpriseSubjectId(baseSubject);
        }

        // Load enterprise subject (id's only).
        Subject enterpriseSubject = null;
        if (loadFullSubject) {
            enterpriseSubject = pm.loadEnterpriseSubject(enterpriseSubjectId);
        } else {
            enterpriseSubject = pm.loadEnterpriseSubjectIdentifiersOnly(enterpriseSubjectId);
        }
        return enterpriseSubject;
    }

    /**
     *
     * @param subjectSearchCriteria
     * @param subject
     * @param subjectIdentifierToRemove
     */
    private void filterSubjectIdentifiers(SubjectSearchCriteria subjectSearchCriteria, Subject subject, SubjectIdentifier subjectIdentifierToRemove) {

        // Strip out the "subjectIdentifierToRemove" (if not null).
        if (subjectIdentifierToRemove != null) {
            subject.removeSubjectIdentifier(subjectIdentifierToRemove);
        }

        // Now should filter identifiers based upon scoping organization (assigning authority).
        if (subjectSearchCriteria.hasScopingAssigningAuthorities()) {

            List<SubjectIdentifier> subjectIdentifiers = subject.getSubjectIdentifiers();
            List<SubjectIdentifier> copyOfSubjectIdentifiers = new ArrayList<SubjectIdentifier>();
            copyOfSubjectIdentifiers.addAll(subjectIdentifiers);

            // Go through each SubjectIdentifier.
            for (SubjectIdentifier subjectIdentifier : copyOfSubjectIdentifiers) {

                // Should we keep it?
                if (!this.shouldKeepSubjectIdentifier(subjectSearchCriteria, subjectIdentifier)) {

                    // Not a match ... disregard id (should not return id).
                    subjectIdentifiers.remove(subjectIdentifier);
                }
            }
        }
    }

    /**
     *
     * @param subjectSearchCriteria
     * @param subjectIdentifier
     * @return
     */
    private boolean shouldKeepSubjectIdentifier(SubjectSearchCriteria subjectSearchCriteria, SubjectIdentifier subjectIdentifier) {
        // Based on calling logic, already determined that scoping assigning authorities exist.

        // Get identifier domain for the given identifier.
        SubjectIdentifierDomain subjectIdentifierDomain = subjectIdentifier.getIdentifierDomain();

        // Now see if we should return the identifier or not.
        boolean shouldKeepSubjectIdentifier = false;
        for (SubjectIdentifierDomain scopingIdentifierDomain : subjectSearchCriteria.getScopingAssigningAuthorities()) {
            if (subjectIdentifierDomain.equals(scopingIdentifierDomain)) {
                shouldKeepSubjectIdentifier = true;
                break;
            }
        }
        return shouldKeepSubjectIdentifier;
    }
}
