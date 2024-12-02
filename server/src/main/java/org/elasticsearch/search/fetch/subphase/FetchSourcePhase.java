/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.subphase;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.mapper.InferenceMetadataFieldsMapper;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.FetchContext;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.fetch.StoredFieldsSpec;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceFilter;

import java.util.Map;

public final class FetchSourcePhase implements FetchSubPhase {
    @Override
    public FetchSubPhaseProcessor getProcessor(FetchContext fetchContext) {
        FetchSourceContext fetchSourceContext = fetchContext.fetchSourceContext();
        if (fetchSourceContext == null || fetchSourceContext.fetchSource() == false) {
            return null;
        }
        assert fetchSourceContext.fetchSource();
        SourceFilter sourceFilter = fetchSourceContext.filter();
        final boolean filterExcludesAll = sourceFilter.excludesAll();
        return new FetchSubPhaseProcessor() {
            private int fastPath;

            @Override
            public void setNextReader(LeafReaderContext readerContext) {

            }

            @Override
            public StoredFieldsSpec storedFieldsSpec() {
                return StoredFieldsSpec.NEEDS_SOURCE;
            }

            @Override
            public void process(HitContext hitContext) {
                String index = fetchContext.getIndexName();
                if (fetchContext.getSearchExecutionContext().isSourceEnabled() == false) {
                    if (fetchSourceContext.hasFilter()) {
                        throw new IllegalArgumentException(
                            "unable to fetch fields from _source field: _source is disabled in the mappings for index [" + index + "]"
                        );
                    }
                    return;
                }
                hitExecute(fetchSourceContext, hitContext);
            }

            private void hitExecute(FetchSourceContext fetchSourceContext, HitContext hitContext) {
                final boolean nestedHit = hitContext.hit().getNestedIdentity() != null;
                Source source = hitContext.source();

                // If this is a parent document and there are no source filters, then add the source as-is.
                if (nestedHit == false && fetchSourceContext.hasFilter() == false) {
                    source = replaceInferenceMetadataFields(hitContext.hit(), source);
                    hitContext.hit().sourceRef(source.internalSourceRef());
                    fastPath++;
                    return;
                }

                if (filterExcludesAll) {
                    // we can just add an empty map
                    source = Source.empty(source.sourceContentType());
                } else {
                    // Otherwise, filter the source and add it to the hit.
                    source = source.filter(sourceFilter);
                }
                if (nestedHit) {
                    source = extractNested(source, hitContext.hit().getNestedIdentity());
                } else {
                    source = replaceInferenceMetadataFields(hitContext.hit(), source);
                }
                hitContext.hit().sourceRef(source.internalSourceRef());
            }

            /**
             * Transfers the {@link InferenceMetadataFieldsMapper#NAME} field from the document fields
             * to the original _source if it has been requested.
             */
            private Source replaceInferenceMetadataFields(SearchHit hit, Source source) {
                if (fetchContext.getSearchExecutionContext()
                    .getIndexSettings()
                    .getIndexVersionCreated()
                    .before(IndexVersions.INFERENCE_METADATA_FIELDS)) {
                    return source;
                }
                var field = hit.removeMetadataFields(InferenceMetadataFieldsMapper.NAME);
                if (field == null || field.getValues().isEmpty()) {
                    return source;
                }
                var newSource = source.source();
                newSource.put(InferenceMetadataFieldsMapper.NAME, field.getValues().get(0));
                return Source.fromMap(newSource, source.sourceContentType());
            }

            @Override
            public Map<String, Object> getDebugInfo() {
                return Map.of("fast_path", fastPath);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Source extractNested(Source in, SearchHit.NestedIdentity nestedIdentity) {
        Map<String, Object> sourceMap = in.source();
        while (nestedIdentity != null) {
            sourceMap = (Map<String, Object>) sourceMap.get(nestedIdentity.getField().string());
            if (sourceMap == null) {
                return Source.empty(in.sourceContentType());
            }
            nestedIdentity = nestedIdentity.getChild();
        }
        return Source.fromMap(sourceMap, in.sourceContentType());
    }
}
