/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.support;

import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.joda.time.DateTime;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.watcher.input.search.ExecutableSearchInput.DEFAULT_SEARCH_TYPE;
import static org.elasticsearch.xpack.watcher.support.WatcherDateTimeUtils.formatDate;
import static org.elasticsearch.xpack.watcher.support.WatcherUtils.flattenModel;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.getRandomSupportedSearchType;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class WatcherUtilsTests extends ESTestCase {
    public void testFlattenModel() throws Exception {
        DateTime now = new DateTime(Clock.systemUTC().millis());
        Map<String, Object> map = new HashMap<>();
        map.put("a", singletonMap("a1", new int[] { 0, 1, 2 }));
        map.put("b", new String[] { "b0", "b1", "b2" });
        map.put("c", Arrays.asList(TimeValue.timeValueSeconds(0), TimeValue.timeValueSeconds(1)));
        map.put("d", now);

        Map<String, Object> result = flattenModel(map);
        assertThat(result.size(), is(9));
        assertThat(result, hasEntry("a.a1.0", "0"));
        assertThat(result, hasEntry("a.a1.1", "1"));
        assertThat(result, hasEntry("a.a1.2", "2"));
        assertThat(result, hasEntry("b.0", "b0"));
        assertThat(result, hasEntry("b.1", "b1"));
        assertThat(result, hasEntry("b.2", "b2"));
        assertThat(result, hasEntry("c.0", "0"));
        assertThat(result, hasEntry("c.1", "1000"));
        assertThat(result, hasEntry("d", formatDate(now)));
    }

    public void testResponseToData() throws Exception {
        final Map<String, Object> expected = new HashMap<>();
        expected.put("key1", "val");
        expected.put("key2", 1);
        expected.put("key3", 1.4);
        expected.put("key4", Arrays.asList("a", "b", "c"));
        Map<String, Object> otherMap = new HashMap<>();
        otherMap.putAll(expected);
        expected.put("key5", otherMap);
        ToXContentObject content = (builder, params) -> {
            builder.startObject();
            for (Map.Entry<String, ?> entry : expected.entrySet()) {
                builder.field(entry.getKey());
                builder.value(entry.getValue());
            }
            builder.endObject();
            return builder;
        };
        Map<String, Object> result = WatcherUtils.responseToData(content);
        assertThat(result, equalTo(expected));
    }

    public void testSerializeSearchRequest() throws Exception {
        String[] expectedIndices = generateRandomStringArray(5, 5, true);
        String[] expectedTypes = generateRandomStringArray(2, 5, true);
        IndicesOptions expectedIndicesOptions = IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), randomBoolean(),
                randomBoolean(), WatcherSearchTemplateRequest.DEFAULT_INDICES_OPTIONS);
        SearchType expectedSearchType = getRandomSupportedSearchType();

        BytesReference expectedSource = null;
        Script expectedTemplate = null;
        WatcherSearchTemplateRequest request;
        if (randomBoolean()) {
            Map<String, Object> params = new HashMap<>();
            if (randomBoolean()) {
                int maxParams = randomIntBetween(1, 10);
                for (int i = 0; i < maxParams; i++) {
                    params.put(randomAlphaOfLengthBetween(1, 5), randomAlphaOfLengthBetween(1, 5));
                }
            }
            String text = randomAlphaOfLengthBetween(1, 5);
            ScriptType scriptType = randomFrom(ScriptType.values());
            expectedTemplate = new Script(scriptType, "mustache", text, params);
            request = new WatcherSearchTemplateRequest(expectedIndices, expectedTypes, expectedSearchType,
                    expectedIndicesOptions, expectedTemplate);
        } else {
            SearchSourceBuilder sourceBuilder = SearchSourceBuilder.searchSource().query(QueryBuilders.matchAllQuery()).size(11);
            XContentBuilder builder = jsonBuilder();
            builder.value(sourceBuilder);
            expectedSource = builder.bytes();
            request = new WatcherSearchTemplateRequest(expectedIndices, expectedTypes, expectedSearchType,
                    expectedIndicesOptions, expectedSource);
        }

        XContentBuilder builder = jsonBuilder();
        request.toXContent(builder, ToXContent.EMPTY_PARAMS);
        XContentParser parser = createParser(builder);
        assertThat(parser.nextToken(), equalTo(XContentParser.Token.START_OBJECT));
        WatcherSearchTemplateRequest result = WatcherSearchTemplateRequest.fromXContent(parser, DEFAULT_SEARCH_TYPE);

        assertThat(result.getIndices(), arrayContainingInAnyOrder(expectedIndices != null ? expectedIndices : new String[0]));
        assertThat(result.getTypes(), arrayContainingInAnyOrder(expectedTypes != null ? expectedTypes : new String[0]));
        assertThat(result.getIndicesOptions(), equalTo(expectedIndicesOptions));
        assertThat(result.getSearchType(), equalTo(expectedSearchType));

        assertNotNull(result.getTemplate());
        assertThat(result.getTemplate().getLang(), equalTo("mustache"));
        if (expectedSource == null) {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(expectedTemplate.getIdOrCode()));
            assertThat(result.getTemplate().getType(), equalTo(expectedTemplate.getType()));
            assertThat(result.getTemplate().getParams(), equalTo(expectedTemplate.getParams()));
        } else {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(expectedSource.utf8ToString()));
            assertThat(result.getTemplate().getType(), equalTo(ScriptType.INLINE));
        }
    }

    public void testDeserializeSearchRequest() throws Exception {

        XContentBuilder builder = jsonBuilder().startObject();

        String[] indices = Strings.EMPTY_ARRAY;
        if (randomBoolean()) {
            indices = generateRandomStringArray(5, 5, false);
            if (randomBoolean()) {
                builder.array("indices", indices);
            } else {
                builder.field("indices", Strings.arrayToCommaDelimitedString(indices));
            }
        }

        String[] types = Strings.EMPTY_ARRAY;
        if (randomBoolean()) {
            types = generateRandomStringArray(2, 5, false);
            if (randomBoolean()) {
                builder.array("types", types);
            } else {
                builder.field("types", Strings.arrayToCommaDelimitedString(types));
            }
        }

        IndicesOptions indicesOptions = WatcherSearchTemplateRequest.DEFAULT_INDICES_OPTIONS;
        if (randomBoolean()) {
            indicesOptions = IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), randomBoolean(),
                    randomBoolean(), WatcherSearchTemplateRequest.DEFAULT_INDICES_OPTIONS);
            builder.startObject("indices_options")
                    .field("allow_no_indices", indicesOptions.allowNoIndices())
                    .field("expand_wildcards", indicesOptions.expandWildcardsClosed() && indicesOptions.expandWildcardsOpen() ? "all" :
                            indicesOptions.expandWildcardsClosed() ? "closed" :
                                    indicesOptions.expandWildcardsOpen() ? "open" :
                                            "none")
                    .field("ignore_unavailable", indicesOptions.ignoreUnavailable())
                    .endObject();
        }

        SearchType searchType = SearchType.DEFAULT;
        if (randomBoolean()) {
            searchType = getRandomSupportedSearchType();
            builder.field("search_type", randomBoolean() ? searchType.name() : searchType.name().toLowerCase(Locale.ROOT));
        }

        BytesReference source = BytesArray.EMPTY;
        if (randomBoolean()) {
            SearchSourceBuilder searchSourceBuilder = SearchSourceBuilder.searchSource().query(QueryBuilders.matchAllQuery()).size(11);
            XContentBuilder searchSourceJsonBuilder = jsonBuilder();
            searchSourceBuilder.toXContent(searchSourceJsonBuilder, ToXContent.EMPTY_PARAMS);
            source = searchSourceBuilder.buildAsBytes(XContentType.JSON);
            builder.rawField("body", source);
        }
        Script template = null;
        if (randomBoolean()) {
            Map<String, Object> params = new HashMap<>();
            if (randomBoolean()) {
                int maxParams = randomIntBetween(1, 10);
                for (int i = 0; i < maxParams; i++) {
                    params.put(randomAlphaOfLengthBetween(1, 5), randomAlphaOfLengthBetween(1, 5));
                }
            }
            String text = randomAlphaOfLengthBetween(1, 5);
            ScriptType scriptType = randomFrom(ScriptType.values());
            template = new Script(scriptType, "mustache", text, params);
            builder.field("template", template);
        }
        builder.endObject();

        XContentParser parser = createParser(builder);
        assertThat(parser.nextToken(), equalTo(XContentParser.Token.START_OBJECT));
        WatcherSearchTemplateRequest result = WatcherSearchTemplateRequest.fromXContent(parser, DEFAULT_SEARCH_TYPE);

        assertThat(result.getIndices(), arrayContainingInAnyOrder(indices));
        assertThat(result.getTypes(), arrayContainingInAnyOrder(types));
        assertThat(result.getIndicesOptions(), equalTo(indicesOptions));
        assertThat(result.getSearchType(), equalTo(searchType));
        if (source == null) {
            assertThat(result.getSearchSource(), nullValue());
        } else {
            assertThat(result.getSearchSource().utf8ToString(), equalTo(source.utf8ToString()));
        }
        if (template == null) {
            assertThat(result.getTemplate(), nullValue());
        } else {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(template.getIdOrCode()));
            assertThat(result.getTemplate().getType(), equalTo(template.getType()));
            assertThat(result.getTemplate().getParams(), equalTo(template.getParams()));
            assertThat(result.getTemplate().getLang(), equalTo("mustache"));
        }
    }

}
