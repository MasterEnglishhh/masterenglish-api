package com.masterenglish.api.modules.content.repository.fragment;

import com.masterenglish.api.modules.content.entity.ContentRevision;

public interface ContentRevisionLocking {
    void refreshForUpdate(ContentRevision revision);
}
