/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.systemgraph.versions;

import static org.neo4j.server.security.systemgraph.UserSecurityGraphComponentVersion.COMMUNITY_SECURITY_40;

import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog;
import org.neo4j.logging.Log;
import org.neo4j.server.security.auth.UserRepository;

/**
 * This is the UserSecurityComponent version for Neo4j 4.0
 */
public class CommunitySecurityComponentVersion_1_40 extends SupportedCommunitySecurityComponentVersion {
    public CommunitySecurityComponentVersion_1_40(
            Log debugLog, AbstractSecurityLog securityLog, UserRepository userRepository) {
        super(COMMUNITY_SECURITY_40, userRepository, debugLog, securityLog);
    }

    @Override
    public boolean detected(Transaction tx) {
        return componentNotInVersionNode(tx) && nodesWithLabelExist(tx, USER_LABEL);
    }

    @Override
    public void upgradeSecurityGraph(Transaction tx, int fromVersion) throws Exception {}

    @Override
    public void upgradeSecurityGraphSchema(Transaction tx, int fromVersion) {}
}
