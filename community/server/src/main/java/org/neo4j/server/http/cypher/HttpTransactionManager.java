/*
 * Copyright (c) 2002-2019 "Neo4j,"
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
package org.neo4j.server.http.cypher;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.kernel.GraphDatabaseQueryService;
import org.neo4j.kernel.impl.query.QueryExecutionEngine;
import org.neo4j.logging.LogProvider;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.time.Clocks;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * An entry point for managing transaction in HTTP API.
 */
public class HttpTransactionManager
{
    // this is how the default DB is referred to in HTTP API,
    // which is different from how it is referred to internally
    private static final String DEFAULT_HTTP_DB_NAME = "data";

    private final TransactionHandleRegistry transactionRegistry;
    private final DatabaseManager databaseManager;
    private final JobScheduler jobScheduler;
    private final LogProvider userLogProvider;
    private final String defaultDatabaseName;

    public HttpTransactionManager( DatabaseManager databaseManager, Config config, JobScheduler jobScheduler, Clock clock, Duration transactionTimeout,
            LogProvider userLogProvider )
    {
        this.databaseManager = databaseManager;
        this.jobScheduler = jobScheduler;
        this.userLogProvider = userLogProvider;

        this.defaultDatabaseName = config.get( GraphDatabaseSettings.default_database );
        transactionRegistry = new TransactionHandleRegistry( clock, transactionTimeout, userLogProvider );
        scheduleTransactionTimeout( transactionTimeout );
    }

    /**
     * Creates and returns a transaction facade for a given database.
     *
     * @param databaseName database name.
     * @return a transaction facade or {@code null} if a database with the supplied database name does not exist.
     */
    public TransactionFacade getTransactionFacade( String databaseName )
    {
        databaseName = transformDbName( databaseName );
        Optional<DatabaseContext> databaseContext = databaseManager.getDatabaseContext( databaseName );
        if ( databaseContext.isEmpty() )
        {
            return null;
        }

        return createTransactionFacade( databaseContext.get() );
    }

    public TransactionHandleRegistry getTransactionHandleRegistry()
    {
        return transactionRegistry;
    }

    private String transformDbName( String databaseName )
    {
        if ( DEFAULT_HTTP_DB_NAME.equals( databaseName ) )
        {
            return defaultDatabaseName;
        }

        return databaseName;
    }

    private TransactionFacade createTransactionFacade( DatabaseContext databaseContext )
    {
        Dependencies dependencyResolver = databaseContext.getDependencies();

        return new TransactionFacade( new TransitionalPeriodTransactionMessContainer( databaseContext.getDatabaseFacade() ),
                dependencyResolver.resolveDependency( QueryExecutionEngine.class ), dependencyResolver.resolveDependency( GraphDatabaseQueryService.class ),
                transactionRegistry, userLogProvider );
    }

    private void scheduleTransactionTimeout( Duration timeout )
    {
        Clock clock = Clocks.systemClock();

        long timeoutMillis = timeout.toMillis();
        jobScheduler.scheduleRecurring( Group.SERVER_TRANSACTION_TIMEOUT, () ->
        {
            long maxAge = clock.millis() - timeoutMillis;
            transactionRegistry.rollbackSuspendedTransactionsIdleSince( maxAge );
        }, timeoutMillis, MILLISECONDS );
    }
}