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
package org.neo4j.bolt.protocol.common.fsm.transition.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.neo4j.bolt.protocol.common.message.request.transaction.CommitMessage;
import org.neo4j.bolt.tx.error.TransactionException;

class CommitTransactionalStateTransitionTest
        extends AbstractTransactionCompletionStateTransitionTest<CommitMessage, CommitTransactionalStateTransition> {

    private static final String MOCK_BOOKMARK = "test-bookmark-1234";

    @BeforeEach
    void prepareBookmark() throws TransactionException {
        Mockito.doReturn(MOCK_BOOKMARK).when(this.transaction).commit();
    }

    @Override
    protected CommitTransactionalStateTransition getTransition() {
        return CommitTransactionalStateTransition.getInstance();
    }

    @Override
    protected CommitMessage getMessage() {
        return CommitMessage.getInstance();
    }

    @Override
    protected InOrder createInOrder() {
        return Mockito.inOrder(this.context, this.connection, this.transaction, this.responseHandler);
    }

    @Override
    protected void verifyInteractions(InOrder inOrder) throws TransactionException {
        inOrder.verify(this.transaction).commit();
    }

    @Override
    protected void verifyCompletion(InOrder inOrder) throws TransactionException {
        inOrder.verify(this.responseHandler).onBookmark(MOCK_BOOKMARK);
    }
}