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
import static javax.ws.rs.core.MediaType.APPLICATION_XHTML_XML;
import static javax.ws.rs.core.MediaType.APPLICATION_XHTML_XML_TYPE;
import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static javax.ws.rs.core.MediaType.TEXT_HTML_TYPE;
import static com.google.common.collect.ImmutableMap.builder;
import static com.hp.hpl.jena.graph.Node.ANY;
import static com.hp.hpl.jena.sparql.util.graph.GraphUtils.multiValueURI;
import static com.hp.hpl.jena.vocabulary.RDF.type;
import static org.fcrepo.kernel.api.RdfLexicon.LDP_NAMESPACE;
import static org.fcrepo.kernel.api.RdfLexicon.REPOSITORY_NAMESPACE;
import static org.fcrepo.kernel.api.RdfCollectors.toModel;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import com.google.common.collect.ImmutableMap;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.rdf.model.Model;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.tools.generic.EscapeTool;
import org.apache.velocity.tools.generic.FieldTool;
import org.fcrepo.http.commons.responses.HtmlTemplate;
import org.fcrepo.http.commons.responses.RdfNamespacedStream;
import org.fcrepo.http.commons.responses.ViewHelpers;
import org.fcrepo.kernel.api.RdfLexicon;
import org.slf4j.Logger;

/**
 * Simple HTML provider for RdfNamespacedStreams
 *
 * @author ajs6f
 * @since Nov 19, 2013
 */
@Provider
@Produces({TEXT_HTML, APPLICATION_XHTML_XML})
public class StreamingBaseHtmlProvider implements MessageBodyWriter<RdfNamespacedStream> {


    @javax.ws.rs.core.Context
    UriInfo uriInfo;

    private static EscapeTool escapeTool = new EscapeTool();

    protected VelocityEngine velocity = new VelocityEngine();

    /**
     * Location in the classpath where Velocity templates are to be found.
     */
    public static final String templatesLocation = "/views";

    /**
     * Location in the classpath where the common css file is to be found.
     */
    public static final String commonCssLocation = "/views/common.css";

    /**
     * Location in the classpath where the common javascript file is to be found.
     */
    public static final String commonJsLocation = "/views/common.js";

    /**
     * A map from String names for primary node types to the Velocity templates
     * that should be used for those node types.
     */
    protected Map<String, Template> templatesMap;

    public static final String templateFilenameExtension = ".vsl";

    public static final String velocityPropertiesLocation =
            "/velocity.properties";

    private static final ViewHelpers VIEW_HELPERS = ViewHelpers.getInstance();

    private static final Logger LOGGER =
        getLogger(StreamingBaseHtmlProvider.class);

    @PostConstruct
    void init() throws IOException {

        LOGGER.trace("Velocity engine initializing...");
        final Properties properties = new Properties();
        final URL propertiesUrl =
                getClass().getResource(velocityPropertiesLocation);
        LOGGER.debug("Using Velocity configuration from {}", propertiesUrl);
        try (final InputStream propertiesStream = propertiesUrl.openStream()) {
            properties.load(propertiesStream);
        }
        velocity.init(properties);
        LOGGER.trace("Velocity engine initialized.");

        LOGGER.trace("Assembling a map of node primary types -> templates...");
        final ImmutableMap.Builder<String, Template> templatesMapBuilder = builder();

        of("jcr:nodetypes", "fcr:versions", "fcr:fixity", "default")
            .forEach(key -> templatesMapBuilder.put(key, velocity.getTemplate(getTemplateLocation(key))));

        templatesMap = templatesMapBuilder
            .put(REPOSITORY_NAMESPACE + "RepositoryRoot", velocity.getTemplate(getTemplateLocation("root")))
            .put(REPOSITORY_NAMESPACE + "Binary", velocity.getTemplate(getTemplateLocation("binary")))
            .put(REPOSITORY_NAMESPACE + "Version", velocity.getTemplate(getTemplateLocation("resource")))
            .put(REPOSITORY_NAMESPACE + "Pairtree", velocity.getTemplate(getTemplateLocation("resource")))
            .put(REPOSITORY_NAMESPACE + "Container", velocity.getTemplate(getTemplateLocation("resource")))
            .put(LDP_NAMESPACE + "NonRdfSource", velocity.getTemplate(getTemplateLocation("binary")))
            .put(LDP_NAMESPACE + "RdfSource", velocity.getTemplate(getTemplateLocation("resource"))).build();

        LOGGER.trace("Assembled template map.");
        LOGGER.trace("HtmlProvider initialization complete.");
    }

    @Override
    public void writeTo(final RdfNamespacedStream nsStream, final Class<?> type,
                        final Type genericType, final Annotation[] annotations,
                        final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders,
                        final OutputStream entityStream) throws IOException {

        final Node subject = VIEW_HELPERS.getContentNode(nsStream.stream.topic());

        final Model model = nsStream.stream.collect(toModel());
        model.setNsPrefixes(nsStream.namespaces);

        final Template nodeTypeTemplate = getTemplate(model, subject, Arrays.asList(annotations));

        final Context context = getContext(model, subject);

        // the contract of MessageBodyWriter<T> is _not_ to close the stream
        // after writing to it
        final Writer outWriter = new OutputStreamWriter(entityStream);
        nodeTypeTemplate.merge(context, outWriter);
        outWriter.flush();
    }

    protected Context getContext(final Model model, final Node subject) {
        final FieldTool fieldTool = new FieldTool();

        final Context context = new VelocityContext();
        context.put("rdfLexicon", fieldTool.in(RdfLexicon.class));
        context.put("helpers", VIEW_HELPERS);
        context.put("esc", escapeTool);
        context.put("rdf", model.getGraph());

        context.put("model", model);
        context.put("subjects", model.listSubjects());
        context.put("nodeany", ANY);
        context.put("topic", subject);
        context.put("uriInfo", uriInfo);
        return context;
    }

    private Template getTemplate(final Model rdf, final Node subject,
                                 final List<Annotation> annotations) {

        final String tplName = annotations.stream().filter(x -> x instanceof HtmlTemplate)
            .map(x -> ((HtmlTemplate) x).value()).filter(templatesMap::containsKey).findFirst()
            .orElseGet(() -> {
                final List<String> types = multiValueURI(rdf.getResource(subject.getURI()), type);
                if (types.contains(REPOSITORY_NAMESPACE + "RepositoryRoot")) {
                    return REPOSITORY_NAMESPACE + "RepositoryRoot";
                }
                return types.stream().filter(templatesMap::containsKey).findFirst().orElse("default");
            });
        LOGGER.debug("Using template: {}", tplName);
        return templatesMap.get(tplName);
    }

    @Override
    public boolean isWriteable(final Class<?> type, final Type genericType,
                               final Annotation[] annotations, final MediaType mediaType) {
        LOGGER.debug(
                "Checking to see if type: {} is serializable to mimeType: {}",
                type.getName(), mediaType);
        return (mediaType.equals(TEXT_HTML_TYPE) || mediaType
                .equals(APPLICATION_XHTML_XML_TYPE))
                && RdfNamespacedStream.class.isAssignableFrom(type);
    }

    @Override
    public long getSize(final RdfNamespacedStream t, final Class<?> type,
                        final Type genericType, final Annotation[] annotations,
                        final MediaType mediaType) {
        // we don't know in advance how large the result might be
        return -1;
    }

    private static String getTemplateLocation(final String nodeTypeName) {
        return templatesLocation + "/" +
            nodeTypeName.replace(':', '-') + templateFilenameExtension;
    }
}
