package com.masterenglish.api.modules.vocabulary.service;

import com.masterenglish.api.modules.vocabulary.dto.response.ContentImportResult;
import java.io.IOException;
import java.nio.file.Path;

public interface ProductionVocabularyImportService {
    ContentImportResult importStarter(String actorEmail, Path manifest) throws IOException;
}
