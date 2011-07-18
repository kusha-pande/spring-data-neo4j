/**
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.neo4j.template;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.helpers.collection.ClosableIterable;
import org.neo4j.helpers.collection.IterableWrapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.neo4j.conversion.QueryResult;
import org.springframework.data.neo4j.conversion.QueryResultBuilder;
import org.springframework.data.neo4j.core.GraphDatabase;
import org.springframework.data.neo4j.core.Property;
import org.springframework.data.neo4j.support.path.NodePath;
import org.springframework.data.neo4j.support.path.PathMapper;
import org.springframework.data.neo4j.support.path.RelationshipPath;
import org.springframework.data.neo4j.support.query.CypherQueryEngine;
import org.springframework.data.neo4j.support.query.QueryEngine;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

public class Neo4jTemplate implements Neo4jOperations {

    private final GraphDatabase graphDatabase;

    private final PlatformTransactionManager transactionManager;

    private final Neo4jExceptionTranslator exceptionTranslator = new Neo4jExceptionTranslator();

    private static void notNull(Object... pairs) {
        assert pairs.length % 2 == 0 : "wrong number of pairs to check";
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == null) {
                throw new InvalidDataAccessApiUsageException("[Assertion failed] - " + pairs[i + 1] + " is required; it must not be null");
            }
        }
    }

    /**
     * @param graphDatabase the neo4j graph database
     * @param transactionManager if passed in, will be used to create implicit transactions whenever needed
     * @return a Neo4jTemplate instance
     */
    public Neo4jTemplate(final GraphDatabase graphDatabase, PlatformTransactionManager transactionManager) {
        notNull(graphDatabase, "graphDatabase");
        this.transactionManager = transactionManager;
        this.graphDatabase = graphDatabase;
    }

    /**
     * @param graphDatabase the neo4j graph database
     * @return a Neo4jTemplate instance
     */
    public Neo4jTemplate(final GraphDatabase graphDatabase) {
        notNull(graphDatabase, "graphDatabase");
        transactionManager = null;
        this.graphDatabase = graphDatabase;
    }


    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return exceptionTranslator.translateExceptionIfPossible(ex);
    }


    private <T> T doExecute(final GraphCallback<T> callback) {
        notNull(callback, "callback");
        try {
            return callback.doWithGraph(graphDatabase);
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        } catch (Exception e) {
            throw new org.springframework.data.neo4j.core.UncategorizedGraphStoreException("Error executing callback",e);
        }
    }

    @Override
    public <T> T exec(final GraphCallback<T> callback) {
        if (transactionManager == null) return doExecute(callback);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(new TransactionCallback<T>() {
            public T doInTransaction(TransactionStatus status) {
                return doExecute(callback);
            }
        });
    }

    @Override
    public Node getReferenceNode() {
        try {
            return graphDatabase.getReferenceNode();
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }

    @Override
    public Node createNode(final Property... properties) {
        return exec(new GraphCallback<Node>() {
            @Override
            public Node doWithGraph(GraphDatabase graph) throws Exception {
                return graphDatabase.createNode(properties);
            }
        });
    }

    @Override
    public Node getNode(long id) {
        if (id < 0) throw new InvalidDataAccessApiUsageException("id is negative");
        try {
            return graphDatabase.getNodeById(id);
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }

    @Override
    public Relationship getRelationship(long id) {
        if (id < 0) throw new InvalidDataAccessApiUsageException("id is negative");
        try {
            return graphDatabase.getRelationshipById(id);
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }

    @Override
    public <T extends PropertyContainer> T index(final String indexName, final T element, final String field, final Object value) {
        notNull(element, "element", field, "field", value, "value",indexName,"indexName");
        exec(new GraphCallback.WithoutResult() {
            @Override
            public void doWithGraphWithoutResult(GraphDatabase graph) throws Exception {
                if (element instanceof Relationship) {
                    Index<Relationship> relationshipIndex = graphDatabase.createIndex(Relationship.class, indexName, false);
                    relationshipIndex.add((Relationship) element, field, value);
                } else if (element instanceof Node) {
                    graphDatabase.createIndex(Node.class, indexName, false).add((Node) element, field, value);
                } else {
                    throw new IllegalArgumentException("Provided element is neither node nor relationship " + element);
                }
            }
        });
        return element;
    }

    @Override
    public <T> QueryResult<T> convert(Iterable<T> iterable) {
        return new QueryResultBuilder<T>(iterable);
    }

    private QueryEngine queryEngineFor(CypherQueryEngine.Type type) {
        return graphDatabase.queryEngineFor(type);
    }

    private <T> ClosableIterable<T> mapNodes(final IndexHits<Node> nodes, final PathMapper<T> pathMapper) {
        assert nodes != null;
        assert pathMapper != null;
        return new IndexHitsIterableWrapper<T,Node>(nodes, pathMapper) {
            @Override
            protected Path createPath(Node node) {
                return new NodePath(node);
            }
        };
    }
    private <T> ClosableIterable<T> mapRelationships(final IndexHits<Relationship> relationships, final PathMapper<T> pathMapper) {
        assert relationships != null;
        assert pathMapper != null;
        return new IndexHitsIterableWrapper<T,Relationship>(relationships, pathMapper) {
            @Override
            protected Path createPath(Relationship relationship) {
                return new RelationshipPath(relationship);
            }
        };
    }

    @Override
    public Relationship createRelationship(final Node startNode, final Node endNode, final RelationshipType relationshipType, final Property... properties) {
        notNull(startNode, "startNode", endNode, "endNode", relationshipType, "relationshipType", properties, "properties");
        return exec(new GraphCallback<Relationship>() {
            @Override
            public Relationship doWithGraph(GraphDatabase graph) throws Exception {
                return graph.createRelationship(startNode, endNode, relationshipType, properties);
            }
        });
    }

    private static abstract class IndexHitsIterableWrapper<T, S extends PropertyContainer> extends IterableWrapper<T, S> implements ClosableIterable<T> {
        private final IndexHits<S> indexHits;
        private final PathMapper<T> pathMapper;

        public IndexHitsIterableWrapper(IndexHits<S> indexHits, PathMapper<T> pathMapper) {
            super(indexHits);
            this.indexHits = indexHits;
            this.pathMapper = pathMapper;
        }

        @Override
        protected T underlyingObjectToObject(S node) {
            return pathMapper.mapPath(createPath(node));
        }

        protected abstract Path createPath(S element);

        @Override
        public void close() {
           indexHits.close();
        }
    }

    @Override
    public QueryResult<Map<String, Object>> query(String statement) {
        notNull(statement, "statement");
        return queryEngineFor(QueryEngine.Type.Cypher).query(statement);
    }

    @Override
    public QueryResult<Path> traverse(Node startNode, TraversalDescription traversal) {
        notNull(startNode, "startNode", traversal, "traversal");
        try {
            return new QueryResultBuilder<Path>(traversal.traverse(startNode));
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }

    @Override
    public <T extends PropertyContainer> QueryResult<T> lookup(String indexName, String field, Object value) {
        notNull(field, "field", value, "value", indexName, "indexName");
        try {
            Index<T> index = graphDatabase.getIndex(indexName);
            return new QueryResultBuilder<T>(index.get(field, value));
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }
    @Override
    public <T extends PropertyContainer> QueryResult<T> lookup(String indexName, Object valueOrQueryObject) {
        notNull(valueOrQueryObject, "valueOrQueryObject", indexName, "indexName");
        try {
            Index<T> index = graphDatabase.getIndex(indexName);
            return new QueryResultBuilder<T>(index.query(valueOrQueryObject));
        } catch (RuntimeException e) {
            throw translateExceptionIfPossible(e);
        }
    }
}

/*
conversion for all query methods

-> target type
-> target type + mapper
-> no conversion (or auto-conversion) -> default mapper

source        Path       Node      Relationship      Primitive, DomainObject
Path ->       Path,      NodePath, RelationshipPath  X          lastNode()
Node ->       endNode(), node,     Mapper            X          NodeEntity
Relationship  lastRel(), Mapper    relationship      X          RelEntity
primitive     X          X         X                 primitive
Map<String,Obj>                                                 Mapper
*/
