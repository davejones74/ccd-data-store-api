package uk.gov.hmcts.ccd.domain.service.getcasedocument;

import static uk.gov.hmcts.ccd.domain.service.common.AccessControlService.CAN_READ;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.ccd.data.caseaccess.CachedCaseUserRepository;
import uk.gov.hmcts.ccd.data.caseaccess.CaseUserRepository;
import uk.gov.hmcts.ccd.data.user.CachedUserRepository;
import uk.gov.hmcts.ccd.data.user.UserRepository;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseField;
import uk.gov.hmcts.ccd.domain.model.definition.CaseType;
import uk.gov.hmcts.ccd.domain.service.common.AccessControlService;
import uk.gov.hmcts.ccd.domain.service.common.CaseTypeService;
import uk.gov.hmcts.ccd.domain.service.getcase.CaseNotFoundException;
import uk.gov.hmcts.ccd.domain.service.getcase.CreatorGetCaseOperation;
import uk.gov.hmcts.ccd.domain.service.getcase.GetCaseOperation;
import uk.gov.hmcts.ccd.endpoint.exceptions.BadRequestException;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocument;
import uk.gov.hmcts.ccd.v2.external.domain.CaseDocumentMetadata;
import uk.gov.hmcts.ccd.v2.external.domain.Permission;

@Service
public class GetCaseDocumentOperation {

    private final GetCaseOperation getCaseOperation;
    private final CaseTypeService caseTypeService;
    private final UserRepository userRepository;
    private final CaseUserRepository caseUserRepository;
    private final DocumentIdValidationService documentIdValidationService;
    private final AccessControlService accessControlService;

    public static final String COMPLEX = "Complex";
    public static final String COLLECTION = "Collection";
    public static final String DOCUMENT = "Document";
    public static final String BAD_REQUEST_EXCEPTION_DOCUMENT_INVALID = "DocumentId is not valid";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    public GetCaseDocumentOperation(
        @Qualifier(CreatorGetCaseOperation.QUALIFIER) final GetCaseOperation getCaseOperation,
        final CaseTypeService caseTypeService,
        @Qualifier(CachedUserRepository.QUALIFIER) UserRepository userRepository,
        @Qualifier(CachedCaseUserRepository.QUALIFIER) CaseUserRepository caseUserRepository,
        DocumentIdValidationService documentIdValidationService,
        AccessControlService accessControlService) {

        this.getCaseOperation = getCaseOperation;
        this.caseTypeService = caseTypeService;
        this.userRepository = userRepository;
        this.caseUserRepository = caseUserRepository;
        this.documentIdValidationService = documentIdValidationService;
        this.accessControlService = accessControlService;
    }


    public CaseDocumentMetadata getCaseDocumentMetadata(String caseId, String documentId) {

        if (!documentIdValidationService.validateDocumentUUID(documentId)) {
            throw new BadRequestException(BAD_REQUEST_EXCEPTION_DOCUMENT_INVALID);
        }

        final CaseDetails caseDetails = this.getCaseOperation.execute(caseId)
            .orElseThrow(() -> new CaseNotFoundException(caseId));

        if (caseDetails.getReferenceAsString().isEmpty()) {
            throw new CaseNotFoundException(caseId);
        }

        return CaseDocumentMetadata.builder()
            .caseId(caseDetails.getReferenceAsString())
            .caseTypeId(caseDetails.getCaseTypeId())
            .jurisdictionId(caseDetails.getJurisdiction())
            .document(getCaseDocument(caseDetails, documentId))
            .build();

    }

    public CaseDocument getCaseDocument(CaseDetails caseDetails, String documentId) {

        String caseTypeId = caseDetails.getCaseTypeId();
        String jurisdictionId = caseDetails.getJurisdiction();
        final CaseType caseType = caseTypeService.getCaseTypeForJurisdiction(caseTypeId, jurisdictionId);

        List<CaseField> documentCaseFields = new ArrayList<>();
        List<CaseField> complexCaseFieldList = caseType.getCaseFields()
            .stream()
            .filter(caseField -> (DOCUMENT.equalsIgnoreCase(caseField.getFieldType().getType())) ||
                                 (COMPLEX.equalsIgnoreCase(caseField.getFieldType().getType())) ||
                                 (COLLECTION.equalsIgnoreCase(caseField.getFieldType().getType())))
            .collect(Collectors.toList());

        if (complexCaseFieldList.isEmpty()) {
            throw new CaseDocumentNotFoundException(String.format("No document field found for CaseType : %s", caseType.getId()));
        }

        extractDocumentFieldsFromCaseDefinition(complexCaseFieldList, documentCaseFields);
        JsonNode documentFieldsWithReadPermission = getDocumentFieldsWithReadPermission(caseDetails, documentCaseFields)
            .orElseThrow((() -> new CaseDocumentNotFoundException("User does not has read permissions on any document field")));

        String documentUrl = getDocumentUrl(documentId, documentFieldsWithReadPermission);

        if (!StringUtils.isEmpty(documentUrl)) {
            //build caseDocument and set permissions
            return CaseDocument.builder()
                .id(documentId)
                .url(documentUrl)
                .permissions(Arrays.asList(Permission.READ))
                .build();
        } else {
            throw new CaseDocumentNotFoundException(
                String.format("No document found for this case reference: %s",
                    caseDetails.getReferenceAsString()));
        }
    }

    private String getDocumentUrl(String documentId, JsonNode readPermission) {
        String urlPatternString = "^(?:\\/\\/|[^\\/]+)*\\/documents\\/[a-zA-Z0-9-]{36}";
        Pattern pattern = Pattern.compile(urlPatternString);

        for (JsonNode jsonNode : readPermission) {
            //within jsonNode, get the documentId exists
            //directly check for binary_url key //extract the document ID


            Iterator<Map.Entry<String, JsonNode>> iterator = jsonNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                if (entry.getValue().asText().contains(documentId)
                    && entry.getKey().toUpperCase().contains("URL")
                    && pattern.matcher(entry.getValue().asText()).matches()) {
                    return entry.getValue().asText();
                }
            }
        }
        return null;
    }

    private void extractDocumentFieldsFromCaseDefinition(List<CaseField> complexCaseFieldList, List<CaseField> documentCaseFields) {
        for (CaseField caseField : complexCaseFieldList) {
            switch (caseField.getFieldType().getType()) {
                case DOCUMENT:
                    documentCaseFields.add(caseField);
                    break;
                case COMPLEX:
                case COLLECTION:
                    //collection can have documents , another complex, another collection,
                    extractDocumentFieldsFromCaseDefinition(caseField.getFieldType().getComplexFields(), documentCaseFields);
                    break;
                default:
                    break;
            }
        }
    }

    private Optional<JsonNode> getDocumentFieldsWithReadPermission(CaseDetails caseDetails, List<CaseField> documentFields) {
        Set<String> roles = getUserRoles(caseDetails.getId());
        return Optional.of(accessControlService.filterCaseFieldsByAccess(
            MAPPER.convertValue(caseDetails.getData(), JsonNode.class),
            documentFields,
            roles,
            CAN_READ, true));
    }

    private Set<String> getUserRoles(String caseId) {
        return Sets.union(userRepository.getUserRoles(),
            new HashSet<>(caseUserRepository
                .findCaseRoles(Long.valueOf(caseId), userRepository.getUserId())));
    }
}
