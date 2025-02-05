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
package org.fcrepo.kernel.api.models;

import java.net.URI;
import java.util.Date;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;

import com.hp.hpl.jena.rdf.model.Resource;

import org.fcrepo.kernel.api.exception.MalformedRdfException;
import org.fcrepo.kernel.api.identifiers.IdentifierConverter;
import org.fcrepo.kernel.api.TripleCategory;
import org.fcrepo.kernel.api.RdfStream;

import com.hp.hpl.jena.rdf.model.Model;

/**
 * @author ajs6f
 * @since Jan 10, 2014
 */
public interface FedoraResource {

    /**
     * @return The JCR node that backs this object.
     */
    Node getNode();

    /**
     * Get the path to the JCR node
     * @return path
     */
    String getPath();

    /**
     * Get the children of this resource
     * @return a stream of Fedora resources
     */
    default Stream<FedoraResource> getChildren() {
        return getChildren(false);
    }

    /**
     * Get the children of this resource, possibly recursively
     * @param recursive whether to recursively fetch child resources
     * @return a stream of Fedora resources
     */
    Stream<FedoraResource> getChildren(Boolean recursive);

    /**
     * Get the container of this resource
     * @return the container of this resource
     */
    FedoraResource getContainer();

    /**
     * Get the child of this resource at the given path
     * @param relPath the given path
     * @return the child of this resource
     */
    FedoraResource getChild(String relPath);

    /**
     * Does this resource have a property
     * @param relPath the given path
     * @return the boolean value whether the resource has a property
     */
    boolean hasProperty(String relPath);

    /**
     * Delete this resource, and any inbound references to it
     */
    void delete();

    /**
     * Get the date this datastream was created
     * @return created date
     */
    Date getCreatedDate();

    /**
     * Get the date this datastream was last modified
     * @return last modified date
     */
    Date getLastModifiedDate();

    /**
     * Check if this object uses a given RDF type
     *
     * <p>Note: the type parameter should be in prefixed short form, so ldp:Container or ex:Image
     * are both acceptable types. This method does not assume any jcr to fedora prefix mappings are
     * managed by the implementation, so hasType("jcr:frozenNode") is a valid use of this method.</p>
     *
     * @param type the given type
     * @return whether the object has the given type
     */
    boolean hasType(final String type);

    /**
     * Get the RDF:type values for this resource
     * @return a list of types for this resource
     */
    List<URI> getTypes();

    /**
     * Update the provided properties with a SPARQL Update query. The updated
     * properties may be serialized to the JCR store.
     *
     * After applying the statement, clients SHOULD check the result
     * of #getDatasetProblems, which may include problems when attempting to
     * serialize the data to JCR.
     *
     * @param idTranslator the property of idTranslator
     * @param sparqlUpdateStatement sparql update statement
     * @param originalTriples original triples
     * @throws MalformedRdfException if malformed rdf exception occurred
     * @throws AccessDeniedException if access denied in updating properties
     */
    void updateProperties(final IdentifierConverter<Resource, FedoraResource> idTranslator,
                          final String sparqlUpdateStatement,
                          final RdfStream originalTriples) throws MalformedRdfException, AccessDeniedException;

    /**
     * Return the RDF properties of this object using the provided context
     * @param idTranslator the property of idTranslator
     * @param context the context
     * @return the rdf properties of this object using the provided context
     */
    RdfStream getTriples(final IdentifierConverter<Resource, FedoraResource> idTranslator,
                         final TripleCategory context);

    /**
     * Return the RDF properties of this object using the provided contexts
     * @param idTranslator the property of idTranslator
     * @param contexts the provided contexts
     * @return the rdf properties of this object
     */
    RdfStream getTriples(final IdentifierConverter<Resource, FedoraResource> idTranslator,
                         final Set<? extends TripleCategory> contexts);

    /**
     * Get the JCR Base version for the node
     *
     * @return base version
     */
    public Version getBaseVersion();

    /**
     * Get JCR VersionHistory for the node.
     *
     * @return version history
     */
    public VersionHistory getVersionHistory();

    /**
     * Check if a resource was created in this session
     * @return if resource created in this session
     */
    Boolean isNew();

    /**
     * Replace the properties of this object with the properties from the given
     * model
     *
     * @param idTranslator the given property of idTranslator
     * @param inputModel the input model
     * @param originalTriples the original triples
     * @throws MalformedRdfException if malformed rdf exception occurred
     */
    void replaceProperties(final IdentifierConverter<Resource, FedoraResource> idTranslator,
                                final Model inputModel,
                                final RdfStream originalTriples) throws MalformedRdfException;

    /**
         * Construct an ETag value from the last modified date and path. JCR has a
     * mix:etag type, but it only takes into account binary properties. We
     * actually want whole-object etag data. TODO : construct and store an ETag
     * value on object modify
     *
     * @return constructed etag value
     */
    String getEtagValue();

    /**
     * Enable versioning
     */
    void enableVersioning();

    /**
     * Disable versioning
     */
    void disableVersioning();

    /**
     * Check if a resource is versioned
     * @return whether the resource is versioned
     */
    boolean isVersioned();

    /**
     * Check if a resource is frozen (a historic version).
     * @return whether the resource is frozen
     */
    boolean isFrozenResource();

    /**
     * When this is a frozen node, get the ancestor that was explicitly versioned
     * @return the ancestor that was explicity versioned
     */
    FedoraResource getVersionedAncestor();

    /**
     * Get the unfrozen equivalent of a frozen versioned node
     * @return the unfrozen equivalent of a frozen versioned node
     */
    FedoraResource getUnfrozenResource();

    /**
     * Get the node for this object at the version provided.
     * @param label the label
     * @return the node for this object at the version provided
     */
    Node getNodeVersion(String label);

    /**
     * This method returns the version label of this frozen resource.
     * If this resource is not frozen, null is returned.
     * @return version label
     */
    String getVersionLabelOfFrozenResource();
}
