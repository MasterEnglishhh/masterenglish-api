package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import java.nio.file.Path;

public interface MediaDecoder {

    /**
     * Decodes and validates a regular staged media file against the declared expected MIME type.
     * Enforces demuxer and codec allowlists, single media stream constraint, dimension/duration
     * bounds, execution timeout, and full decode verification (detecting corrupt or truncated
     * files).
     *
     * @param stagedFile path to the staged file on disk
     * @param expectedMimeType MIME type declared during upload reservation
     * @return validated media information extracted from the file
     * @throws com.masterenglish.api.common.error.BusinessException if validation, probe, or decode
     *     fails
     */
    DecodedMediaInfo decode(Path stagedFile, String expectedMimeType);
}
