/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.candidates.IndexedCandidate
import timber.log.Timber
import kotlin.math.min

class CandidatesPagingSource(
    val fcitx: FcitxConnection,
    val total: Int,
    val offset: Int,
    val structuredCandidates: Array<IndexedCandidate>,
) : PagingSource<Int, IndexedCandidate>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val structured = loadStructuredCandidates(startIndex, pageSize)
        val fallbackOffset = maxOf(startIndex, structuredCandidates.size)
        val fallbackLimit = pageSize - structured.size
        val fallback = if (fallbackLimit > 0) {
            fcitx.runOnReady {
                getStructuredCandidates(fallbackOffset, fallbackLimit)
            }.toIndexedCandidates(offset = fallbackOffset)
        } else {
            emptyList()
        }
        val candidates = structured + fallback
        val loadedCount = candidates.size
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        val nextKey = if (total > 0) {
            if (startIndex + loadedCount >= total) null else startIndex + loadedCount
        } else {
            if (loadedCount < pageSize) null else startIndex + loadedCount
        }
        return LoadResult.Page(candidates, prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>) = null

    private fun loadStructuredCandidates(startIndex: Int, pageSize: Int): List<IndexedCandidate> {
        if (startIndex >= structuredCandidates.size) return emptyList()
        val endIndex = min(startIndex + pageSize, structuredCandidates.size)
        return structuredCandidates.slice(startIndex until endIndex)
    }
}

internal fun Array<FcitxEvent.Candidate>.toIndexedCandidates(offset: Int): List<IndexedCandidate> = mapIndexed { index, candidate ->
    IndexedCandidate(candidate = candidate, sourceIndex = offset + index)
}
