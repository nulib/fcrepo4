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
package org.fcrepo.http.api;


import static com.hp.hpl.jena.rdf.model.ResourceFactory.createResource;
import static javax.ws.rs.core.MediaType.APPLICATION_XHTML_XML;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static javax.ws.rs.core.MediaType.WILDCARD;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.NOT_IMPLEMENTED;
import static javax.ws.rs.core.Response.Status.UNSUPPORTED_MEDIA_TYPE;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.notAcceptable;
import static javax.ws.rs.core.Response.ok;

import static javax.ws.rs.core.Variant.mediaTypes;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.jena.riot.WebContent.contentTypeSPARQLUpdate;
import static org.fcrepo.http.commons.domain.RDFMediaType.JSON_LD;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_ALT2;
import static org.fcrepo.http.commons.domain.RDFMediaType.NTRIPLES;
import static org.fcrepo.http.commons.domain.RDFMediaType.RDF_XML;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE_X;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_BINARY;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_CONTAINER;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_PAIRTREE;
import static org.fcrepo.kernel.api.RdfLexicon.LDP_NAMESPACE;
import static org.fcrepo.kernel.modeshape.services.TransactionServiceImpl.getCurrentTransactionId;
import static org.fcrepo.kernel.modeshape.utils.NamespaceTools.getNamespaces;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.annotation.PostConstruct;
import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilderException;
import javax.ws.rs.core.Variant;

import org.fcrepo.http.commons.domain.ContentLocation;
import org.fcrepo.http.commons.domain.PATCH;
import org.fcrepo.http.commons.responses.RdfNamespacedStream;
import org.fcrepo.kernel.api.exception.InvalidChecksumException;
import org.fcrepo.kernel.api.exception.MalformedRdfException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.models.Container;
import org.fcrepo.kernel.api.models.FedoraBinary;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.NonRdfSourceDescription;
import org.fcrepo.kernel.api.rdf.DefaultRdfStream;
import org.fcrepo.kernel.api.RdfStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.base.Splitter;
import static com.google.common.base.Strings.nullToEmpty;

/**
 * @author cabeer
 * @author ajs6f
 * @since 9/25/14
 */

@Scope("request")
@Path("/{path: .*}")
public class FedoraLdp extends ContentExposingResource {

    private static final Logger LOGGER = getLogger(FedoraLdp.class);

    private static final Splitter.MapSplitter RFC3230_SPLITTER =
        Splitter.on(',').omitEmptyStrings().trimResults().
        withKeyValueSeparator(Splitter.on('=').limit(2));

    @PathParam("path") protected String externalPath;

    @Inject private FedoraHttpConfiguration httpConfiguration;

    /**
     * Default JAX-RS entry point
     */
    public FedoraLdp() {
        super();
    }

    /**
     * Create a new FedoraNodes instance for a given path
     * @param externalPath the external path
     */
    @VisibleForTesting
    public FedoraLdp(final String externalPath) {
        this.externalPath = externalPath;
    }

    /**
     * Run these actions after initializing this resource
     */
    @PostConstruct
    public void postConstruct() {
        setUpJMSInfo(uriInfo, headers);
    }

    /**
     * Retrieve the node headers
     * @return response
     */
    @HEAD
    @Timed
    public Response head() {
        LOGGER.info("HEAD for: {}", externalPath);

        checkCacheControlHeaders(request, servletResponse, resource(), session);

        addResourceHttpHeaders(resource());

        final Response.ResponseBuilder builder = ok();

        if (resource() instanceof FedoraBinary) {
            builder.type(((FedoraBinary) resource()).getMimeType());
        }

        return builder.build();
    }

    /**
     * Outputs information about the supported HTTP methods, etc.
     * @return the outputs information about the supported HTTP methods, etc.
     */
    @OPTIONS
    @Timed
    public Response options() {
        LOGGER.info("OPTIONS for '{}'", externalPath);
        addOptionsHttpHeaders();
        return ok().build();
    }


    /**
     * Retrieve the node profile
     *
     * @param rangeValue the range value
     * @return a binary or the triples for the specified node
     * @throws IOException if IO exception occurred
     */
    @GET
    @Produces({TURTLE + ";qs=10", JSON_LD + ";qs=8",
            N3, N3_ALT2, RDF_XML, NTRIPLES, APPLICATION_XML, TEXT_PLAIN, TURTLE_X,
            TEXT_HTML, APPLICATION_XHTML_XML})
    public Response getResource(@HeaderParam("Range") final String rangeValue) throws IOException {
        checkCacheControlHeaders(request, servletResponse, resource(), session);

        LOGGER.info("GET resource '{}'", externalPath);

        final RdfStream rdfStream = new DefaultRdfStream(asNode(resource()));

        // If requesting a binary, check the mime-type if "Accept:" header is present.
        // (This needs to be done before setting up response headers, as getContent
        // returns a response - so changing headers after that won't work so nicely.)
        final ImmutableList<MediaType> acceptableMediaTypes = ImmutableList.copyOf(headers.getAcceptableMediaTypes());

        if (resource() instanceof FedoraBinary && acceptableMediaTypes.size() > 0) {
            final MediaType mediaType = MediaType.valueOf(((FedoraBinary) resource()).getMimeType());

            if (!acceptableMediaTypes.stream().anyMatch(t -> t.isCompatible(mediaType))) {
                return notAcceptable( Variant.VariantListBuilder.newInstance().mediaTypes(mediaType).build()).build();
            }
        }

        addResourceHttpHeaders(resource());
        return getContent(rangeValue, getChildrenLimit(), rdfStream);
    }

    private int getChildrenLimit() {
        final List<String> acceptHeaders = headers.getRequestHeader(HttpHeaders.ACCEPT);
        if (acceptHeaders != null && acceptHeaders.size() > 0) {
            final List<String> accept = Arrays.asList(acceptHeaders.get(0).split(","));
            if (accept.contains(TEXT_HTML) || accept.contains(APPLICATION_XHTML_XML)) {
                // Magic number '100' is tied to common-metadata.vsl display of ellipses
                return 100;
            }
        }

        final List<String> limits = headers.getRequestHeader("Limit");
        if (null != limits && limits.size() > 0) {
            try {
                return Integer.parseInt(limits.get(0));

            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid 'Limit' header value: {}", limits.get(0));
                throw new ClientErrorException("Invalid 'Limit' header value: " + limits.get(0), SC_BAD_REQUEST, e);
            }
        }
        return -1;
    }

    /**
     * Deletes an object.
     *
     * @return response
     */
    @DELETE
    @Timed
    public Response deleteObject() {
        evaluateRequestPreconditions(request, servletResponse, resource(), session);

        LOGGER.info("Delete resource '{}'", externalPath);
        resource().delete();

        try {
            session.save();
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }

        return noContent().build();
    }

    /**
     * Create a resource at a specified path, or replace triples with provided RDF.
     * @param requestContentType the request content type
     * @param requestBodyStream the request body stream
     * @param checksum the checksum value
     * @param contentDisposition the content disposition value
     * @param ifMatch the if-match value
     * @param link the link value
     * @return 204
     * @throws InvalidChecksumException if invalid checksum exception occurred
     * @throws MalformedRdfException if malformed rdf exception occurred
     */
    public Response createOrReplaceObjectRdf(
            @HeaderParam("Content-Type") final MediaType requestContentType,
            @ContentLocation final InputStream requestBodyStream,
            @QueryParam("checksum") final String checksum,
            @HeaderParam("Content-Disposition") final ContentDisposition contentDisposition,
            @HeaderParam("If-Match") final String ifMatch,
            @HeaderParam("Link") final String link)
            throws InvalidChecksumException, MalformedRdfException {
        return createOrReplaceObjectRdf(requestContentType, requestBodyStream,
            checksum, contentDisposition, ifMatch, link, null);
    }

    /**
     * Create a resource at a specified path, or replace triples with provided RDF.
     *
     * Temporary 6 parameter version of this function to allow for backwards
     * compatability during a period of transition from a digest hash being
     * provided via non-standard 'checksum' query parameter to RFC-3230 compliant
     * 'Digest' header.
     *
     * TODO: Remove this function in favour of the 5 parameter version that takes
     *       the Digest header in lieu of the checksum parameter
     *       https://jira.duraspace.org/browse/FCREPO-1851
     *
     * @param requestContentType the request content type
     * @param requestBodyStream the request body stream
     * @param checksumDeprecated the deprecated digest hash
     * @param contentDisposition the content disposition value
     * @param ifMatch the if-match value
     * @param link the link value
     * @param digest the digest header
     * @return 204
     * @throws InvalidChecksumException if invalid checksum exception occurred
     * @throws MalformedRdfException if malformed rdf exception occurred
     */
    @PUT
    @Consumes
    @Timed
    public Response createOrReplaceObjectRdf(
            @HeaderParam("Content-Type") final MediaType requestContentType,
            @ContentLocation final InputStream requestBodyStream,
            @QueryParam("checksum") final String checksumDeprecated,
            @HeaderParam("Content-Disposition") final ContentDisposition contentDisposition,
            @HeaderParam("If-Match") final String ifMatch,
            @HeaderParam("Link") final String link,
            @HeaderParam("Digest") final String digest)
            throws InvalidChecksumException, MalformedRdfException {

        checkLinkForLdpResourceCreation(link);

        final FedoraResource resource;

        final String path = toPath(translator(), externalPath);

        // TODO: Add final when deprecated checksum Query paramater is removed
        // https://jira.duraspace.org/browse/FCREPO-1851
        String checksum = parseDigestHeader(digest);

        final MediaType contentType = getSimpleContentType(requestContentType);

        if (nodeService.exists(session, path)) {
            resource = resource();
        } else {
            final MediaType effectiveContentType
                    = requestBodyStream == null || requestContentType == null ? null : contentType;
            resource = createFedoraResource(path, effectiveContentType, contentDisposition);
        }

        if (httpConfiguration.putRequiresIfMatch() && StringUtils.isBlank(ifMatch) && !resource.isNew()) {
            throw new ClientErrorException("An If-Match header is required", 428);
        }

        evaluateRequestPreconditions(request, servletResponse, resource, session);

        final RdfStream resourceTriples;
        final boolean created = resource.isNew();

        if (created) {
            resourceTriples = new DefaultRdfStream(asNode(resource()));
        } else {
            resourceTriples = getResourceTriples();
        }

        LOGGER.info("PUT resource '{}'", externalPath);
        if (resource instanceof FedoraBinary) {
            if (!StringUtils.isBlank(checksumDeprecated) && StringUtils.isBlank(digest)) {
                addChecksumDeprecationHeader(resource);
                checksum = checksumDeprecated;
            }
            replaceResourceBinaryWithStream((FedoraBinary) resource,
                    requestBodyStream, contentDisposition, requestContentType, checksum);
        } else if (isRdfContentType(contentType.toString())) {
            replaceResourceWithStream(resource, requestBodyStream, contentType, resourceTriples);
        } else if (!created) {
            boolean emptyRequest = true;
            try {
                emptyRequest = requestBodyStream.read() == -1;
            } catch (final IOException ex) {
                LOGGER.debug("Error checking for request body content", ex);
            }

            if (requestContentType == null && emptyRequest) {
                throw new ClientErrorException("Resource Already Exists", CONFLICT);
            }
            throw new ClientErrorException("Invalid Content Type " + requestContentType, UNSUPPORTED_MEDIA_TYPE);
        }

        try {
            session.save();
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }

        return createUpdateResponse(resource, created);
    }

    /**
     * Update an object using SPARQL-UPDATE
     *
     * @param requestBodyStream the request body stream
     * @return 201
     * @throws MalformedRdfException if malformed rdf exception occurred
     * @throws AccessDeniedException if exception updating property occurred
     * @throws IOException if IO exception occurred
     */
    @PATCH
    @Consumes({contentTypeSPARQLUpdate})
    @Timed
    public Response updateSparql(@ContentLocation final InputStream requestBodyStream)
            throws IOException, MalformedRdfException, AccessDeniedException {

        if (null == requestBodyStream) {
            throw new BadRequestException("SPARQL-UPDATE requests must have content!");
        }

        if (resource() instanceof FedoraBinary) {
            throw new BadRequestException(resource() + " is not a valid object to receive a PATCH");
        }

        try {
            final String requestBody = IOUtils.toString(requestBodyStream);
            if (isBlank(requestBody)) {
                throw new BadRequestException("SPARQL-UPDATE requests must have content!");
            }

            evaluateRequestPreconditions(request, servletResponse, resource(), session);

            final RdfStream resourceTriples;

            if (resource().isNew()) {
                resourceTriples = new DefaultRdfStream(asNode(resource()));
            } else {
                resourceTriples = getResourceTriples();
            }

            LOGGER.info("PATCH for '{}'", externalPath);
            patchResourcewithSparql(resource(), requestBody, resourceTriples);

            session.save();

            addCacheControlHeaders(servletResponse, resource(), session);

            return noContent().build();
        } catch (final IllegalArgumentException iae) {
            throw new BadRequestException(iae.getMessage());
        } catch ( final RuntimeException ex ) {
            final Throwable cause = ex.getCause();
            if (cause instanceof PathNotFoundException) {
                // the sparql update referred to a repository resource that doesn't exist
                throw new BadRequestException(cause.getMessage());
            }
            throw ex;
        }  catch (final RepositoryException e) {
            if (e instanceof AccessDeniedException) {
                throw new AccessDeniedException(e.getMessage());
            }
            throw new RepositoryRuntimeException(e);
        }
    }

    /**
     * Creates a new object.
     *
     * application/octet-stream;qs=1001 is a workaround for JERSEY-2636, to ensure
     * requests without a Content-Type get routed here.
     *
     * @param checksum the checksum value
     * @param contentDisposition the content Disposition value
     * @param requestContentType the request content type
     * @param slug the slug value
     * @param requestBodyStream the request body stream
     * @param link the link value
     * @return 201
     * @throws InvalidChecksumException if invalid checksum exception occurred
     * @throws IOException if IO exception occurred
     * @throws MalformedRdfException if malformed rdf exception occurred
     * @throws AccessDeniedException if access denied in creating resource
     */
    public Response createObject(@QueryParam("checksum") final String checksum,
                                 @HeaderParam("Content-Disposition") final ContentDisposition contentDisposition,
                                 @HeaderParam("Content-Type") final MediaType requestContentType,
                                 @HeaderParam("Slug") final String slug,
                                 @ContentLocation final InputStream requestBodyStream,
                                 @HeaderParam("Link") final String link)
            throws InvalidChecksumException, IOException, MalformedRdfException, AccessDeniedException {
        return createObject(checksum, contentDisposition, requestContentType, slug, requestBodyStream, link, null);
    }
    /**
     * Creates a new object.
     *
     * application/octet-stream;qs=1001 is a workaround for JERSEY-2636, to ensure
     * requests without a Content-Type get routed here.
     *
     * @param checksumDeprecated the checksum value
     * @param contentDisposition the content Disposition value
     * @param requestContentType the request content type
     * @param slug the slug value
     * @param requestBodyStream the request body stream
     * @param link the link value
     * @param digest the digest header
     * @return 201
     * @throws InvalidChecksumException if invalid checksum exception occurred
     * @throws IOException if IO exception occurred
     * @throws MalformedRdfException if malformed rdf exception occurred
     * @throws AccessDeniedException if access denied in creating resource
     */
    @POST
    @Consumes({MediaType.APPLICATION_OCTET_STREAM + ";qs=1001", WILDCARD})
    @Timed
    @Produces({TURTLE + ";qs=10", JSON_LD + ";qs=8",
            N3, N3_ALT2, RDF_XML, NTRIPLES, APPLICATION_XML, TEXT_PLAIN, TURTLE_X,
            TEXT_HTML, APPLICATION_XHTML_XML, "*/*"})
    public Response createObject(@QueryParam("checksum") final String checksumDeprecated,
                                 @HeaderParam("Content-Disposition") final ContentDisposition contentDisposition,
                                 @HeaderParam("Content-Type") final MediaType requestContentType,
                                 @HeaderParam("Slug") final String slug,
                                 @ContentLocation final InputStream requestBodyStream,
                                 @HeaderParam("Link") final String link,
                                 @HeaderParam("Digest") final String digest)
            throws InvalidChecksumException, IOException, MalformedRdfException, AccessDeniedException {

        checkLinkForLdpResourceCreation(link);

        if (!(resource() instanceof Container)) {
            throw new ClientErrorException("Object cannot have child nodes", CONFLICT);
        } else if (resource().hasType(FEDORA_PAIRTREE)) {
            throw new ClientErrorException("Objects cannot be created under pairtree nodes", FORBIDDEN);
        }

        final MediaType contentType = getSimpleContentType(requestContentType);

        final String contentTypeString = contentType.toString();

        final String newObjectPath = mintNewPid(slug);

        // TODO: Add final when deprecated checksum Query paramater is removed
        // https://jira.duraspace.org/browse/FCREPO-1851
        String checksum = parseDigestHeader(digest);

        LOGGER.info("Ingest with path: {}", newObjectPath);

        final MediaType effectiveContentType
                = requestBodyStream == null || requestContentType == null ? null : contentType;
        resource = createFedoraResource(newObjectPath, effectiveContentType, contentDisposition);

        final RdfStream resourceTriples;

        if (resource.isNew()) {
            resourceTriples = new DefaultRdfStream(asNode(resource()));
        } else {
            resourceTriples = getResourceTriples();
        }

        if (requestBodyStream == null) {
            LOGGER.trace("No request body detected");
        } else {
            LOGGER.trace("Received createObject with a request body and content type \"{}\"", contentTypeString);

            if ((resource instanceof Container)
                    && isRdfContentType(contentTypeString)) {
                replaceResourceWithStream(resource, requestBodyStream, contentType, resourceTriples);
            } else if (resource instanceof FedoraBinary) {
                LOGGER.trace("Created a datastream and have a binary payload.");
                if (!StringUtils.isBlank(checksumDeprecated) && StringUtils.isBlank(digest)) {
                    addChecksumDeprecationHeader(resource);
                    checksum = checksumDeprecated;
                }
                replaceResourceBinaryWithStream((FedoraBinary) resource,
                        requestBodyStream, contentDisposition, requestContentType, checksum);

            } else if (contentTypeString.equals(contentTypeSPARQLUpdate)) {
                LOGGER.trace("Found SPARQL-Update content, applying..");
                patchResourcewithSparql(resource, IOUtils.toString(requestBodyStream), resourceTriples);
            } else {
                if (requestBodyStream.read() != -1) {
                    throw new ClientErrorException("Invalid Content Type " + contentTypeString, UNSUPPORTED_MEDIA_TYPE);
                }
            }
        }

        try {
            session.save();
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }

        LOGGER.debug("Finished creating resource with path: {}", newObjectPath);
        return createUpdateResponse(resource, true);
    }

    /**
     * Create the appropriate response after a create or update request is processed.  When a resource is created,
     * examine the Prefer and Accept headers to determine whether to include a representation.  By default, the
     * URI for the created resource is return as plain text.  If a minimal response is requested, then no body is
     * returned.  If a non-minimal return is requested, return the RDF for the created resource in the appropriate
     * RDF serialization.
     *
     * @param resource The created or updated Fedora resource.
     * @param created True for a newly-created resource, false for an updated resource.
     * @return 204 No Content (for updated resources), 201 Created (for created resources) including the resource
     *    URI or content depending on Prefer headers.
     */
    private Response createUpdateResponse(final FedoraResource resource, final boolean created) {
        addCacheControlHeaders(servletResponse, resource, session);
        addResourceLinkHeaders(resource, created);
        if (!created) {
            return noContent().build();
        }

        final URI location = getUri(resource);
        final Response.ResponseBuilder builder = created(location);

        if (prefer == null || !prefer.hasReturn()) {
            final MediaType acceptablePlainText = acceptabePlainTextMediaType();
            if (acceptablePlainText != null) {
                return builder.type(acceptablePlainText).entity(location.toString()).build();
            } else {
                return notAcceptable(mediaTypes(TEXT_PLAIN_TYPE).build()).build();
            }
        } else if (prefer.getReturn().getValue().equals("minimal")) {
            return builder.build();
        } else {
            servletResponse.addHeader("Vary", "Accept, Range, Accept-Encoding, Accept-Language");
            if (prefer != null) {
                prefer.getReturn().addResponseHeaders(servletResponse);
            }
            final RdfNamespacedStream rdfStream = new RdfNamespacedStream(
                new DefaultRdfStream(asNode(resource()), getResourceTriples()), getNamespaces(session()));
            return builder.entity(rdfStream).build();
        }
    }

    /**
     * Returns an acceptable plain text media type if possible, or null if not.
     */
    private MediaType acceptabePlainTextMediaType() {
        final List<MediaType> acceptable = headers.getAcceptableMediaTypes();
        if (acceptable == null || acceptable.size() == 0) {
            return TEXT_PLAIN_TYPE;
        } else {
            for (final MediaType type : acceptable ) {
                if (type.isWildcardType() || (type.isCompatible(TEXT_PLAIN_TYPE) && type.isWildcardSubtype())) {
                    return TEXT_PLAIN_TYPE;
                } else if (type.isCompatible(TEXT_PLAIN_TYPE)) {
                    return type;
                }
            }
        }
        return null;
    }

    @Override
    protected void addResourceHttpHeaders(final FedoraResource resource) {
        super.addResourceHttpHeaders(resource);

        if (getCurrentTransactionId(session) != null) {
            final String canonical = translator().reverse()
                    .convert(resource)
                    .toString()
                    .replaceFirst("/tx:[^/]+", "");


            servletResponse.addHeader("Link", "<" + canonical + ">;rel=\"canonical\"");

        }

        addOptionsHttpHeaders();
    }

    @Override
    protected String externalPath() {
        return externalPath;
    }

    private void addOptionsHttpHeaders() {
        final String options;

        if (resource() instanceof FedoraBinary) {
            options = "DELETE,HEAD,GET,PUT,OPTIONS";

        } else if (resource() instanceof NonRdfSourceDescription) {
            options = "MOVE,COPY,DELETE,POST,HEAD,GET,PUT,PATCH,OPTIONS";
            servletResponse.addHeader("Accept-Patch", contentTypeSPARQLUpdate);

        } else if (resource() instanceof Container) {
            options = "MOVE,COPY,DELETE,POST,HEAD,GET,PUT,PATCH,OPTIONS";
            servletResponse.addHeader("Accept-Patch", contentTypeSPARQLUpdate);

            final String rdfTypes = TURTLE + "," + N3 + ","
                    + N3_ALT2 + "," + RDF_XML + "," + NTRIPLES;
            servletResponse.addHeader("Accept-Post", rdfTypes + "," + MediaType.MULTIPART_FORM_DATA
                    + "," + contentTypeSPARQLUpdate);
        } else {
            options = "";
        }

        addResourceLinkHeaders(resource());

        servletResponse.addHeader("Allow", options);
    }

    private void addResourceLinkHeaders(final FedoraResource resource) {
        addResourceLinkHeaders(resource, false);
    }

    private void addResourceLinkHeaders(final FedoraResource resource, final boolean includeAnchor) {
        if (resource instanceof NonRdfSourceDescription) {
            final URI uri = getUri(((NonRdfSourceDescription) resource).getDescribedResource());
            final Link link = Link.fromUri(uri).rel("describes").build();
            servletResponse.addHeader("Link", link.toString());
        } else if (resource instanceof FedoraBinary) {
            final URI uri = getUri(((FedoraBinary) resource).getDescription());
            final Link.Builder builder = Link.fromUri(uri).rel("describedby");

            if (includeAnchor) {
                builder.param("anchor", getUri(resource).toString());
            }
            servletResponse.addHeader("Link", builder.build().toString());
        }


    }
    /**
     * Add a deprecation notice via the Warning header as per
     * RFC-7234 https://tools.ietf.org/html/rfc7234#section-5.5
     */
    private void addChecksumDeprecationHeader(final FedoraResource resource) {
        servletResponse.addHeader("Warning", "Specifying a SHA-1 Checksum via query parameter is deprecated.");
    }

    private static String getRequestedObjectType(final MediaType requestContentType,
                                          final ContentDisposition contentDisposition) {

        if (requestContentType != null) {
            final String s = requestContentType.toString();
            if (!s.equals(contentTypeSPARQLUpdate) && !isRdfContentType(s) || s.equals(TEXT_PLAIN)) {
                return FEDORA_BINARY;
            }
        }

        if (contentDisposition != null && contentDisposition.getType().equals("attachment")) {
            return FEDORA_BINARY;
        }

        return FEDORA_CONTAINER;
    }

    private FedoraResource createFedoraResource(final String path,
                                                final MediaType requestContentType,
                                                final ContentDisposition contentDisposition) {
        final String objectType = getRequestedObjectType(requestContentType, contentDisposition);

        final FedoraResource result;

        if (objectType.equals(FEDORA_BINARY)) {
            result = binaryService.findOrCreate(session, path);
        } else {
            result = containerService.findOrCreate(session, path);
        }

        return result;
    }

    private String mintNewPid(final String slug) {
        String pid;

        if (slug != null && !slug.isEmpty()) {
            pid = slug;
        } else if (pidMinter != null) {
            pid = pidMinter.get();
        } else {
            pid = defaultPidMinter.get();
        }
        // reverse translate the proffered or created identifier
        LOGGER.trace("Using external identifier {} to create new resource.", pid);
        LOGGER.trace("Using prefixed external identifier {} to create new resource.", uriInfo.getBaseUri() + "/"
                + pid);

        final URI newResourceUri = uriInfo.getAbsolutePathBuilder().clone().path(FedoraLdp.class)
                .resolveTemplate("path", pid, false).build();

        pid = translator().asString(createResource(newResourceUri.toString()));
        try {
            pid = URLDecoder.decode(pid, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            // noop
        }
        // remove leading slash left over from translation
        LOGGER.trace("Using internal identifier {} to create new resource.", pid);

        if (nodeService.exists(session, pid)) {
            LOGGER.trace("Resource with path {} already exists; minting new path instead", pid);
            return mintNewPid(null);
        }

        return pid;
    }

    private void checkLinkForLdpResourceCreation(final String link) {
        if (link != null) {
            try {
                final Link linq = Link.valueOf(link);
                if ("type".equals(linq.getRel()) && (LDP_NAMESPACE + "Resource").equals(linq.getUri().toString())) {
                    LOGGER.info("Unimplemented LDPR creation requested with header link: {}", link);
                    throw new ServerErrorException("LDPR creation not implemented", NOT_IMPLEMENTED);
                }
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException | e instanceof UriBuilderException) {
                    throw new ClientErrorException("Invalid link specified: " + link, BAD_REQUEST);
                }
                throw e;
            }
        }
    }

    /**
     * Parse the RFC-3230 Digest response header value.  Look for a
     * sha1 checksum and return it as a urn, if missing or malformed
     * an empty string is returned.
     * @param digest The Digest header value
     * @return the sha1 checksum value
     */
    private String parseDigestHeader(final String digest) {
        try {
            final Map<String,String> digestPairs = RFC3230_SPLITTER.split(nullToEmpty(digest));
            return digestPairs.entrySet().stream()
                .filter(s -> s.getKey().toLowerCase().equals("sha1"))
                .map(Map.Entry::getValue)
                .findFirst()
                .map("urn:sha1:"::concat)
                .orElse("");
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) {
                throw new ClientErrorException("Invalid Digest header: " + digest + "\n", BAD_REQUEST);
            }
            throw e;
        }
    }
}
