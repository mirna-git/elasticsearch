/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link Field} for storing a {@link Term} along with its start and end offsets.
 */
public final class OffsetSourceField extends Field {
    private static final FieldType FIELD_TYPE = new FieldType();

    static {
        FIELD_TYPE.setTokenized(false);
        FIELD_TYPE.setOmitNorms(true);
        FIELD_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    }

    private int startOffset;
    private int endOffset;

    public OffsetSourceField(String fieldName, String sourceFieldName, int startOffset, int endOffset) {
        super(fieldName, sourceFieldName, FIELD_TYPE);
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public void setValues(String fieldName, int startOffset, int endOffset) {
        this.fieldsData = fieldName;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    @Override
    public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
        OffsetTokenStream stream;
        if (reuse instanceof OffsetTokenStream) {
            stream = (OffsetTokenStream) reuse;
        } else {
            stream = new OffsetTokenStream();
        }

        stream.setValues((String) fieldsData, startOffset, endOffset);
        return stream;
    }

    public static OffsetSourceLoader loader(Terms terms, String fieldName) throws IOException {
        return new OffsetSourceLoader(terms, fieldName);
    }

    private static final class OffsetTokenStream extends TokenStream {
        private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
        private final OffsetAttribute offsetAttribute = addAttribute(OffsetAttribute.class);
        private boolean used = true;
        private String value = null;
        private int startOffset = 0;
        private int endOffset = 0;

        private OffsetTokenStream() {}

        /** Sets the values */
        void setValues(String value, int startOffset, int endOffset) {
            this.value = value;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean incrementToken() {
            if (used) {
                return false;
            }
            clearAttributes();
            termAttribute.append(value);
            offsetAttribute.setOffset(startOffset, endOffset);
            used = true;
            return true;
        }

        @Override
        public void reset() {
            used = false;
        }

        @Override
        public void close() {
            value = null;
        }
    }

    public static class OffsetSourceLoader {
        private final Map<String, PostingsEnum> postingsEnums = new LinkedHashMap<>();

        private OffsetSourceLoader(Terms terms, String fieldName) throws IOException {
            Automaton prefixAutomaton = PrefixQuery.toAutomaton(new BytesRef(fieldName + "."));
            var termsEnum = terms.intersect(new CompiledAutomaton(prefixAutomaton, false, true, false), null);
            while (termsEnum.next() != null) {
                var postings = termsEnum.postings(null, PostingsEnum.OFFSETS);
                if (postings.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                    String sourceFieldName = termsEnum.term().utf8ToString().substring(fieldName.length() + 1);
                    postingsEnums.put(sourceFieldName, postings);
                }
            }
        }

        public OffsetSourceFieldMapper.OffsetSource advanceTo(int doc) throws IOException {
            for (var it = postingsEnums.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                var postings = entry.getValue();
                if (postings.docID() < doc) {
                    if (postings.advance(doc) == DocIdSetIterator.NO_MORE_DOCS) {
                        it.remove();
                        continue;
                    }
                }
                if (postings.docID() == doc) {
                    assert postings.freq() == 1;
                    postings.nextPosition();
                    return new OffsetSourceFieldMapper.OffsetSource(entry.getKey(), postings.startOffset(), postings.endOffset());
                }
            }
            return null;
        }
    }
}
