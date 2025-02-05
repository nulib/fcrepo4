/*
 * Copyright 2015 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.http.api.responses;

import static java.util.stream.Stream.of;
import static com.hp.hpl.jena.graph.NodeFactory.createLiteral;
import static com.hp.hpl.jena.graph.NodeFactory.createURI;
import static com.hp.hpl.jena.vocabulary.RDF.type;
import static java.util.Collections.singletonMap;
import static javax.ws.rs.core.MediaType.TEXT_HTML_TYPE;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static org.fcrepo.kernel.api.RdfLexicon.REPOSITORY_NAMESPACE;
import static org.fcrepo.kernel.modeshape.utils.NamespaceTools.getNamespaces;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

import com.google.common.collect.ImmutableMap;
import org.apache.velocity.Template;
import org.apache.velocity.context.Context;
import org.fcrepo.http.commons.responses.HtmlTemplate;
import org.fcrepo.http.commons.responses.RdfNamespacedStream;
import org.fcrepo.kernel.api.rdf.DefaultRdfStream;
import org.fcrepo.kernel.api.RdfStream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.hp.hpl.jena.graph.Triple;

/**
 * <p>BaseHtmlProviderTest class.</p>
 *
 * @author awoods
 */
public class StreamingBaseHtmlProviderTest {

    private final StreamingBaseHtmlProvider testProvider = new StreamingBaseHtmlProvider();

    private RdfNamespacedStream testData;
    private RdfNamespacedStream testData2;

    @Mock
    private Session mockSession;

    @Mock
    private Workspace mockWorkspace;

    @Mock
    private NamespaceRegistry mockNamespaceRegistry;

    @Before
    public void setup() throws RepositoryException {
        initMocks(this);

        when(mockSession.getWorkspace()).thenReturn(mockWorkspace);
        when(mockWorkspace.getNamespaceRegistry()).thenReturn(mockNamespaceRegistry);
        when(mockNamespaceRegistry.getPrefixes()).thenReturn(new String[]{ });

        testData = new RdfNamespacedStream(
                new DefaultRdfStream(createURI("test:subject"), of(
                    new Triple(createURI("test:subject"),
                            createURI("test:predicate"),
                            createLiteral("test:object")),
                    new Triple(createURI("test:subject"),
                            type.asNode(), createURI(REPOSITORY_NAMESPACE + "Binary")))),
                getNamespaces(mockSession));

        testData2 = new RdfNamespacedStream(
                new DefaultRdfStream(createURI("test:subject2"), of(
                    new Triple(createURI("test:subject2"),
                            type.asNode(), createURI(REPOSITORY_NAMESPACE + "Container")))),
                getNamespaces(mockSession));
        final UriInfo info = Mockito.mock(UriInfo.class);
        setField(testProvider, "uriInfo", info);
    }

    @Test
    public void testIsWriteable() {
        assertTrue(
                "Gave false response to HtmlProvider.isWriteable() that contained legitimate combination of parameters",
                testProvider.isWriteable(RdfNamespacedStream.class, RdfNamespacedStream.class,
                        null, TEXT_HTML_TYPE));
        assertFalse(
                "Gave true response to HtmlProvider.isWriteable() with an incorrect combination of parameters",
                testProvider.isWriteable(RdfStream.class, RdfStream.class,
                        null, TEXT_HTML_TYPE));
        assertFalse(
                "HtmlProvider.isWriteable() should return false if asked to serialize a non-RdfNamespacedStream!",
                testProvider.isWriteable(StreamingBaseHtmlProvider.class,
                        StreamingBaseHtmlProvider.class, null, TEXT_HTML_TYPE));
        assertFalse(
                "HtmlProvider.isWriteable() should return false to text/plain!",
                testProvider.isWriteable(RdfNamespacedStream.class, RdfNamespacedStream.class,
                        null, TEXT_PLAIN_TYPE));
    }

    @Test
    public void testGetSize() {
        assertEquals("Returned wrong size from HtmlProvider!", testProvider
                .getSize(null, null, null, null, null), -1);

    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testWriteTo() throws WebApplicationException,
            IllegalArgumentException, IOException {
        final Template mockTemplate = mock(Template.class);
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        doAnswer(new Answer<Object>() {

            @Override
            public Object answer(final InvocationOnMock invocation) {
                outStream.write("abcdefighijk".getBytes(), 0, 10);
                return "I am pretending to merge a template for you.";
            }
        }).when(mockTemplate).merge(isA(Context.class), isA(Writer.class));
        setField(testProvider, "templatesMap", singletonMap(REPOSITORY_NAMESPACE + "Binary",
                mockTemplate));
        testProvider.writeTo(testData, RdfNamespacedStream.class, mock(Type.class),
                new Annotation[]{}, MediaType.valueOf("text/html"),
                (MultivaluedMap) new MultivaluedHashMap<>(), outStream);
        final byte[] results = outStream.toByteArray();
        assertTrue("Got no output from serialization!", results.length > 0);

    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testWriteToWithAnnotation() throws WebApplicationException,
            IllegalArgumentException, IOException {
        final Template mockTemplate = mock(Template.class);
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        doAnswer(new Answer<Object>() {

            @Override
            public Object answer(final InvocationOnMock invocation) {
                outStream.write("abcdefighijk".getBytes(), 0, 10);
                return "I am pretending to merge a template for you.";
            }
        }).when(mockTemplate).merge(isA(Context.class), isA(Writer.class));

        setField(testProvider, "templatesMap",
                ImmutableMap.of("some:file", mockTemplate));
        final HtmlTemplate mockAnnotation = mock(HtmlTemplate.class);
        when(mockAnnotation.value()).thenReturn("some:file");
        testProvider.writeTo(testData, RdfNamespacedStream.class, mock(Type.class),
                new Annotation[]{mockAnnotation}, MediaType
                        .valueOf("text/html"),
                (MultivaluedMap) new MultivaluedHashMap<>(), outStream);
        final byte[] results = outStream.toByteArray();
        assertTrue("Got no output from serialization!", results.length > 0);

    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testWriteToWithParentTemplate() throws WebApplicationException,
            IllegalArgumentException, IOException {
        final Template mockTemplate = mock(Template.class);
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        doAnswer(new Answer<Object>() {

            @Override
                public Object answer(final InvocationOnMock invocation) {
                outStream.write("abcdefighijk".getBytes(), 0, 10);
                return "I am pretending to merge a template for you.";
            }
        }).when(mockTemplate).merge(isA(Context.class), isA(Writer.class));

        setField(testProvider, "templatesMap",
                 ImmutableMap.of(REPOSITORY_NAMESPACE + "Container", mockTemplate));
        testProvider.writeTo(testData2, RdfNamespacedStream.class, mock(Type.class),
                new Annotation[] {}, MediaType
                        .valueOf("text/html"),
                (MultivaluedMap) new MultivaluedHashMap<>(), outStream);
        final byte[] results = outStream.toByteArray();
        assertTrue("Got no output from serialization!", results.length > 0);
    }
}
