/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.lexer;

import com.questdb.cairo.AbstractCairoTest;
import com.questdb.cairo.CairoTestUtils;
import com.questdb.cairo.Engine;
import com.questdb.cairo.TableModel;
import com.questdb.cairo.sql.CairoEngine;
import com.questdb.common.ColumnType;
import com.questdb.common.PartitionBy;
import com.questdb.griffin.lexer.model.CreateTableModel;
import com.questdb.griffin.lexer.model.ParsedModel;
import com.questdb.griffin.lexer.model.QueryModel;
import com.questdb.std.Files;
import com.questdb.std.str.Path;
import com.questdb.test.tools.TestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;

public class SqlLexerOptimiserTest extends AbstractCairoTest {
    private final static CairoEngine engine = new Engine(configuration);
    private final static SqlLexerOptimiser parser = new SqlLexerOptimiser(engine, configuration);

    @AfterClass
    public static void tearDown() throws IOException {
        engine.close();
    }

    @Test
    public void testAliasWithSpace() throws Exception {
        assertModel("x 'b a' where x > 1",
                "x 'b a' where x > 1",
                modelOf("x").col("x", ColumnType.INT));
    }

    @Test
    public void testAliasWithSpaceX() {
        assertSyntaxError("from x 'a b' where x > 1", 7, "Unexpected");
    }

    @Test
    public void testAmbiguousColumn() {
        assertSyntaxError("orders join customers on customerId = customerId", 25, "Ambiguous",
                modelOf("orders").col("customerId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testAnalyticOrderDirection() throws Exception {
        assertModel(
                "select-analytic a, b, f(c) my over (partition by b order by ts desc, x, y) from (xyz)",
                "select a,b, f(c) my over (partition by b order by ts desc, x asc, y) from xyz",
                modelOf("xyz")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("c", ColumnType.INT)
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    @Test
    public void testAsOfJoin() throws ParserException {
        assertModel(
                "trades t timestamp (timestamp) asof join quotes q timestamp (timestamp) post-join-where tag = null",
                "trades t asof join quotes q where tag = null",
                modelOf("trades").timestamp().col("tag", ColumnType.SYMBOL),
                modelOf("quotes").timestamp()
        );
    }

    @Test
    public void testAsOfJoinOrder() throws Exception {
        assertModel(
                "customers c asof join employees e on e.employeeId = c.customerId join orders o on o.customerId = c.customerId",
                "customers c" +
                        " asof join employees e on c.customerId = e.employeeId" +
                        " join orders o on c.customerId = o.customerId",
                modelOf("customers").col("customerId", ColumnType.SYMBOL),
                modelOf("employees").col("employeeId", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.SYMBOL));
    }

    @Test
    public void testAsOfJoinSubQuery() throws Exception {
        // execution order must be (src: SQL Server)
        //        1. FROM
        //        2. ON
        //        3. JOIN
        //        4. WHERE
        //        5. GROUP BY
        //        6. WITH CUBE or WITH ROLLUP
        //        7. HAVING
        //        8. SELECT
        //        9. DISTINCT
        //        10. ORDER BY
        //        11. TOP
        //
        // which means "where" clause for "e" table has to be explicitly as post-join-where
        assertModel(
                "customers c" +
                        " asof join" +
                        " (select-virtual '1' blah, lastName, employeeId, timestamp" +
                        " from (employees) order by lastName) e on e.employeeId = c.customerId" +
                        " post-join-where e.lastName = 'x' and e.blah = 'y'" +
                        " join orders o on o.customerId = c.customerId",
                "customers c" +
                        " asof join (select '1' blah, lastName, employeeId, timestamp from employees order by lastName) e on c.customerId = e.employeeId" +
                        " join orders o on c.customerId = o.customerId where e.lastName = 'x' and e.blah = 'y'",
                modelOf("customers")
                        .col("customerId", ColumnType.SYMBOL),
                modelOf("employees")
                        .col("employeeId", ColumnType.STRING)
                        .col("lastName", ColumnType.STRING)
                        .col("timestamp", ColumnType.TIMESTAMP),
                modelOf("orders")
                        .col("customerId", ColumnType.SYMBOL)
        );
    }

    @Test
    public void testAsOfJoinSubQuerySimpleAlias() throws Exception {
        assertModel(
                "customers c" +
                        " asof join" +
                        " (select-virtual '1' blah, lastName, customerId, timestamp" +
                        " from (select-choose lastName, employeeId customerId, timestamp" +
                        " from (employees)) order by lastName) a on a.customerId = c.customerId",
                "customers c" +
                        " asof join (select '1' blah, lastName, employeeId customerId, timestamp from employees order by lastName) a on (customerId)",
                modelOf("customers")
                        .col("customerId", ColumnType.SYMBOL),
                modelOf("employees")
                        .col("employeeId", ColumnType.STRING)
                        .col("lastName", ColumnType.STRING)
                        .col("timestamp", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testAsOfJoinSubQuerySimpleNoAlias() throws Exception {
        assertModel(
                "customers c" +
                        " asof join" +
                        " (select-virtual '1' blah, lastName, customerId, timestamp" +
                        " from (select-choose lastName, employeeId customerId, timestamp" +
                        " from (employees)) order by lastName) _xQdbA0 on _xQdbA0.customerId = c.customerId",
                "customers c" +
                        " asof join (select '1' blah, lastName, employeeId customerId, timestamp from employees order by lastName) on (customerId)",
                modelOf("customers").col("customerId", ColumnType.SYMBOL),
                modelOf("employees")
                        .col("employeeId", ColumnType.STRING)
                        .col("lastName", ColumnType.STRING)
                        .col("timestamp", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testCount() throws Exception {
        assertModel(
                "select-group-by" +
                        " customerId," +
                        " count() count " +
                        "from" +
                        " (select-choose c.customerId customerId from (customers c outer join orders o on o.customerId = c.customerId post-join-where o.customerId = NaN))",
                "select c.customerId, count() from customers c" +
                        " outer join orders o on c.customerId = o.customerId " +
                        " where o.customerId = NaN",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("product", ColumnType.STRING)
        );
    }

    @Test
    public void testCreateTable() throws ParserException {
        final String sql = "create table x (" +
                "a INT, " +
                "b BYTE, " +
                "c SHORT, " +
                "d LONG, " +
                "e FLOAT, " +
                "f DOUBLE, " +
                "g DATE, " +
                "h BINARY, " +
                "t TIMESTAMP, " +
                "x SYMBOL, " +
                "z STRING, " +
                "y BOOLEAN) " +
                "timestamp(t) " +
                "partition by MONTH " +
                "record hint 100";

        String expectedNames[] = {"a", "b", "c", "d", "e", "f", "g", "h", "t", "x", "z", "y"};
        int expectedTypes[] = {
                ColumnType.INT,
                ColumnType.BYTE,
                ColumnType.SHORT,
                ColumnType.LONG,
                ColumnType.FLOAT,
                ColumnType.DOUBLE,
                ColumnType.DATE,
                ColumnType.BINARY,
                ColumnType.TIMESTAMP,
                ColumnType.SYMBOL,
                ColumnType.STRING,
                ColumnType.BOOLEAN
        };

        String expectedIndexColumns[] = {};
        int expectedBlockSizes[] = {};

        assertTableModel(sql, expectedNames, expectedTypes, expectedIndexColumns, expectedBlockSizes, 8, "MONTH");
    }

    @Test
    public void testCreateTableInPlaceIndex() throws ParserException {
        final String sql = "create table x (" +
                "a INT, " +
                "b BYTE, " +
                "c SHORT, " +
                "d LONG index, " + // <-- index here
                "e FLOAT, " +
                "f DOUBLE, " +
                "g DATE, " +
                "h BINARY, " +
                "t TIMESTAMP, " +
                "x SYMBOL index, " + // <-- index here
                "z STRING, " +
                "y BOOLEAN) " +
                "timestamp(t) " +
                "partition by YEAR " +
                "record hint 100";

        String expectedNames[] = {"a", "b", "c", "d", "e", "f", "g", "h", "t", "x", "z", "y"};
        int expectedTypes[] = {
                ColumnType.INT,
                ColumnType.BYTE,
                ColumnType.SHORT,
                ColumnType.LONG,
                ColumnType.FLOAT,
                ColumnType.DOUBLE,
                ColumnType.DATE,
                ColumnType.BINARY,
                ColumnType.TIMESTAMP,
                ColumnType.SYMBOL,
                ColumnType.STRING,
                ColumnType.BOOLEAN
        };

        String expectedIndexColumns[] = {"d", "x"};
        int expectedBlockSizes[] = {configuration.getIndexValueBlockSize(), configuration.getIndexValueBlockSize()};
        assertTableModel(sql, expectedNames, expectedTypes, expectedIndexColumns, expectedBlockSizes, 8, "YEAR");
    }

    @Test
    public void testCreateTableInPlaceIndexAndBlockSize() throws ParserException {
        final String sql = "create table x (" +
                "a INT index block size 16, " +
                "b BYTE, " +
                "c SHORT, " +
                "t TIMESTAMP, " +
                "d LONG, " +
                "e FLOAT, " +
                "f DOUBLE, " +
                "g DATE, " +
                "h BINARY, " +
                "x SYMBOL index block size 128, " +
                "z STRING, " +
                "y BOOLEAN) " +
                "timestamp(t) " +
                "partition by MONTH " +
                "record hint 100";

        String expectedNames[] = {"a", "b", "c", "t", "d", "e", "f", "g", "h", "x", "z", "y"};
        int expectedTypes[] = {
                ColumnType.INT,
                ColumnType.BYTE,
                ColumnType.SHORT,
                ColumnType.TIMESTAMP,
                ColumnType.LONG,
                ColumnType.FLOAT,
                ColumnType.DOUBLE,
                ColumnType.DATE,
                ColumnType.BINARY,
                ColumnType.SYMBOL,
                ColumnType.STRING,
                ColumnType.BOOLEAN
        };
        String expectedIndexColumns[] = {"a", "x"};
        int expectedBlockSizes[] = {16, 128};
        assertTableModel(sql, expectedNames, expectedTypes, expectedIndexColumns, expectedBlockSizes, 3, "MONTH");
    }

    @Test
    public void testCreateTableMissingDef() {
        assertSyntaxError("create table xyx", 13, "Unexpected");
    }

    @Test
    public void testCreateTableMissingName() {
        assertSyntaxError("create table ", 12, "Unexpected");
    }

    @Test
    public void testCreateTableWithIndex() throws ParserException {

        final String sql = "create table x (" +
                "a INT index block size 16, " +
                "b BYTE, " +
                "c SHORT, " +
                "d LONG, " +
                "e FLOAT, " +
                "f DOUBLE, " +
                "g DATE, " +
                "h BINARY, " +
                "t TIMESTAMP, " +
                "x SYMBOL, " +
                "z STRING, " +
                "y BOOLEAN)" +
                ", index(x) " +
                "timestamp(t) " +
                "partition by MONTH " +
                "record hint 100";

        String expectedNames[] = {"a", "b", "c", "d", "e", "f", "g", "h", "t", "x", "z", "y"};
        int expectedTypes[] = {
                ColumnType.INT,
                ColumnType.BYTE,
                ColumnType.SHORT,
                ColumnType.LONG,
                ColumnType.FLOAT,
                ColumnType.DOUBLE,
                ColumnType.DATE,
                ColumnType.BINARY,
                ColumnType.TIMESTAMP,
                ColumnType.SYMBOL,
                ColumnType.STRING,
                ColumnType.BOOLEAN
        };
        String expectedIndexColumns[] = {"a", "x"};
        int expectedBlockSizes[] = {16, configuration.getIndexValueBlockSize()};
        assertTableModel(sql, expectedNames, expectedTypes, expectedIndexColumns, expectedBlockSizes, 8, "MONTH");
    }

    @Test
    public void testCreateUnsupported() {
        assertSyntaxError("create object x", 7, "table");
    }

    @Test
    public void testCrossJoin() {
        assertSyntaxError("select x from a a cross join b on b.x = a.x", 31, "cannot");
    }

    @Test
    public void testCrossJoin2() throws Exception {
        assertModel(
                "select-choose a.x x from (a a cross join b z)",
                "select a.x from a a cross join b z",
                modelOf("a").col("x", ColumnType.INT),
                modelOf("b").col("x", ColumnType.INT));
    }

    @Test
    public void testCrossJoin3() throws Exception {
        assertModel(
                "select-choose a.x x from (a a join c on c.x = a.x cross join b z)",
                "select a.x from a a " +
                        "cross join b z " +
                        "join c on a.x = c.x",
                modelOf("a").col("x", ColumnType.INT),
                modelOf("b").col("x", ColumnType.INT),
                modelOf("c").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testCrossJoinNoAlias() throws Exception {
        assertModel("select-choose a.x x from (a a join c on c.x = a.x cross join b)",
                "select a.x from a a " +
                        "cross join b " +
                        "join c on a.x = c.x",
                modelOf("a").col("x", ColumnType.INT),
                modelOf("b").col("x", ColumnType.INT),
                modelOf("c").col("x", ColumnType.INT));
    }

    @Test
    public void testDisallowDotInColumnAlias() {
        assertSyntaxError("select x x.y, y from tab order by x", 9, "not allowed");
    }

    @Test
    public void testDisallowedColumnAliases() throws ParserException {
        assertModel(
                "select-virtual x + z column, x - z column1, x * z column2, x / z column3, x % z column4, x ^ z column5 from (tab1)",
                "select x+z, x-z, x*z, x/z, x%z, x^z from tab1",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    @Test
    public void testDuplicateAlias() {
        assertSyntaxError("customers a" +
                        " cross join orders a", 30, "Duplicate",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("product", ColumnType.STRING)
        );
    }

    @Test
    public void testDuplicateTables() throws Exception {
        assertModel(
                "customers cross join customers",
                "customers cross join customers",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("product", ColumnType.STRING)
        );
    }

    @Test
    public void testEmptyGroupBy() {
        assertSyntaxError("select x, y from tab sample by", 28, "end of input");
    }

    @Test
    public void testEmptyOrderBy() {
        assertSyntaxError("select x, y from tab order by", 27, "end of input");
    }

    @Test
    public void testEqualsConstantTransitivityLhs() throws Exception {
        assertModel(
                "customers c outer join (orders o where o.customerId = 100) o on o.customerId = c.customerId where 100 = c.customerId",
                "customers c" +
                        " outer join orders o on c.customerId = o.customerId" +
                        " where 100 = c.customerId",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testEqualsConstantTransitivityRhs() throws Exception {
        assertModel(
                "customers c outer join (orders o where o.customerId = 100) o on o.customerId = c.customerId where c.customerId = 100",
                "customers c" +
                        " outer join orders o on c.customerId = o.customerId" +
                        " where c.customerId = 100",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testExtraComma2OrderByInAnalyticFunction() {
        assertSyntaxError("select a,b, f(c) my over (partition by b order by ts,) from xyz", 53, "literal expected");
    }

    @Test
    public void testExtraCommaOrderByInAnalyticFunction() {
        assertSyntaxError("select a,b, f(c) my over (partition by b order by ,ts) from xyz", 50, "literal");
    }

    @Test
    public void testExtraCommaPartitionByInAnalyticFunction() {
        assertSyntaxError("select a,b, f(c) my over (partition by b, order by ts) from xyz", 48, ") expected");
    }

    @Test
    public void testFilterOnSubQuery() throws Exception {
        // todo: missing compulsory select-choose after joins that dereferences ambiguous colums
        assertModel(
                "(select-group-by" +
                        " customerId," +
                        " customerName," +
                        " count() count " +
                        "from" +
                        " (customers where customerId > 400 and customerId < 1200)) c" +
                        " outer join orders o on o.customerId = c.customerId post-join-where o.orderId = NaN " +
                        "where count > 1 order by c.customerId",
                "(select customerId, customerName, count() count from customers) c" +
                        " outer join orders o on c.customerId = o.customerId " +
                        " where o.orderId = NaN and c.customerId > 400 and c.customerId < 1200 and count > 1 order by c.customerId",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testGenericPreFilterPlacement() throws Exception {
        assertModel(
                "select-choose customerName, orderId, productId" +
                        " from (" +
                        "customers" +
                        " join (orders where product = 'X') on orders.customerId = customers.customerId where customerName ~ 'WTBHZVPVZZ')",
                "select customerName, orderId, productId " +
                        "from customers join orders on customers.customerId = orders.customerId where customerName ~ 'WTBHZVPVZZ' and product = 'X'",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("product", ColumnType.STRING).col("orderId", ColumnType.INT).col("productId", ColumnType.INT)
        );
    }

    @Test
    public void testInnerJoin() throws Exception {
        assertModel(
                "select-choose a.x x from (a a join b on b.x = a.x)",
                "select a.x from a a inner join b on b.x = a.x",
                modelOf("a").col("x", ColumnType.INT),
                modelOf("b").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testInnerJoin2() throws Exception {
        assertModel(
                "customers join orders on orders.customerId = customers.customerId where customerName ~ 'WTBHZVPVZZ'",
                "customers join orders on customers.customerId = orders.customerId where customerName ~ 'WTBHZVPVZZ'",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testInnerJoinEqualsConstant() throws Exception {
        assertModel(
                "customers join (orders where productName = 'WTBHZVPVZZ') on orders.customerId = customers.customerId",
                "customers join orders on customers.customerId = orders.customerId where productName = 'WTBHZVPVZZ'",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING));
    }

    @Test
    public void testInnerJoinEqualsConstantLhs() throws Exception {
        assertModel(
                "customers join (orders where 'WTBHZVPVZZ' = productName) on orders.customerId = customers.customerId",
                "customers join orders on customers.customerId = orders.customerId where 'WTBHZVPVZZ' = productName",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING));
    }

    @Test
    public void testInnerJoinSubQuery() throws Exception {
        assertModel(
                "select-choose customerName, productName, orderId" +
                        " from (" +
                        "(select-choose customerName, orderId, productId, productName from (" +
                        "customers" +
                        " join (orders where productName ~ 'WTBHZVPVZZ') on orders.customerId = customers.customerId)" +
                        ") x" +
                        " join products p on p.productId = x.productId)",
                "select customerName, productName, orderId from (" +
                        "select customerName, orderId, productId, productName " +
                        "from customers join orders on customers.customerId = orders.customerId where productName ~ 'WTBHZVPVZZ'" +
                        ") x" +
                        " join products p on p.productId = x.productId",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING).col("productId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT)
        );

        assertModel(
                "select-choose customerName, productName, orderId from (customers join (orders o where productName ~ 'WTBHZVPVZZ') o on o.customerId = customers.customerId join products p on p.productId = o.productId)",
                "select customerName, productName, orderId " +
                        " from customers join orders o on customers.customerId = o.customerId " +
                        " join products p on p.productId = o.productId" +
                        " where productName ~ 'WTBHZVPVZZ'",
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING).col("productId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT)
        );
    }

    @Test
    public void testInvalidAlias() {
        assertSyntaxError("orders join customers on orders.customerId = c.customerId", 45, "alias",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING).col("productId", ColumnType.INT)
        );
    }

    @Test
    public void testInvalidColumn() {
        assertSyntaxError("orders join customers on customerIdx = customerId", 25, "Invalid column",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT).col("productName", ColumnType.STRING).col("productId", ColumnType.INT)
        );
    }

    @Test
    public void testInvalidColumnInExpression() {
        assertSyntaxError(
                "select a + b x from tab",
                11,
                "Invalid column",
                modelOf("tab").col("a", ColumnType.INT));
    }

    @Test
    public void testInvalidGroupBy1() {
        assertSyntaxError("select x, y from tab sample by x,", 32, "Unexpected");
    }

    @Test
    public void testInvalidGroupBy2() {
        assertSyntaxError("select x, y from (tab sample by x,)", 33, "')' expected");
    }

    @Test
    public void testInvalidGroupBy3() {
        assertSyntaxError("select x, y from tab sample by x, order by y", 32, "Unexpected token: ,");
    }

    @Test
    public void testInvalidInnerJoin1() {
        assertSyntaxError("select x from a a inner join b z", 31, "'on'");
    }

    @Test
    public void testInvalidInnerJoin2() {
        assertSyntaxError("select x from a a inner join b z on", 33, "Expression");
    }

    @Test
    public void testInvalidOrderBy1() {
        assertSyntaxError("select x, y from tab order by x,", 31, "end of input");
    }

    @Test
    public void testInvalidOrderBy2() {
        assertSyntaxError("select x, y from (tab order by x,)", 33, "Expression expected");
    }

    @Test
    public void testInvalidOuterJoin1() {
        assertSyntaxError("select x from a a outer join b z", 31, "'on'");
    }

    @Test
    public void testInvalidOuterJoin2() {
        assertSyntaxError("select x from a a outer join b z on", 33, "Expression");
    }

    @Test
    public void testInvalidSelectColumn() {
        assertSyntaxError("select c.customerId, orderIdx, o.productId from " +
                        "customers c " +
                        "join (" +
                        "orders latest by customerId where customerId in (`customers where customerName ~ 'PJFSREKEUNMKWOF'`)" +
                        ") o on c.customerId = o.customerId", 21, "Invalid column",
                modelOf("customers").col("customerName", ColumnType.STRING).col("customerId", ColumnType.INT),
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT)
        );

        assertSyntaxError("select c.customerId, orderId, o.productId2 from " +
                        "customers c " +
                        "join (" +
                        "orders latest by customerId where customerId in (`customers where customerName ~ 'PJFSREKEUNMKWOF'`)" +
                        ") o on c.customerId = o.customerId", 30, "Invalid column",
                modelOf("customers").col("customerName", ColumnType.STRING).col("customerId", ColumnType.INT),
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT)
        );

        assertSyntaxError("select c.customerId, orderId, o2.productId from " +
                        "customers c " +
                        "join (" +
                        "orders latest by customerId where customerId in (`customers where customerName ~ 'PJFSREKEUNMKWOF'`)" +
                        ") o on c.customerId = o.customerId", 30, "Invalid table name",
                modelOf("customers").col("customerName", ColumnType.STRING).col("customerId", ColumnType.INT),
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testInvalidSubQuery() {
        assertSyntaxError("select x,y from (tab where x = 100) latest by x", 36, "latest");
    }

    @Test
    public void testInvalidTableName() {
        assertSyntaxError("orders join customer on customerId = customerId", 12, "does not exist",
                modelOf("orders").col("customerId", ColumnType.INT));
    }

    @Test
    public void testJoin1() throws Exception {
        assertModel(
                "select-choose t1.x x, y from " +
                        "(" +
                        "(select-choose x from " +
                        "(" +
                        "tab t2 latest by x where x > 100)) t1 " +
                        "join tab2 xx2 on xx2.x = t1.x " +
                        "join (select-choose x, y from (tab4 latest by z where a > b and y > 0)) x4 on x4.x = t1.x " +
                        "cross join tab3 post-join-where xx2.x > tab3.b" +
                        ")",
                "select t1.x, y from (select x from tab t2 latest by x where x > 100) t1 " +
                        "join tab2 xx2 on xx2.x = t1.x " +
                        "join tab3 on xx2.x > tab3.b " +
                        "join (select x,y from tab4 latest by z where a > b) x4 on x4.x = t1.x " +
                        "where y > 0",
                modelOf("tab").col("x", ColumnType.INT),
                modelOf("tab2").col("x", ColumnType.INT),
                modelOf("tab3").col("b", ColumnType.INT),
                modelOf("tab4").col("x", ColumnType.INT).col("y", ColumnType.INT).col("z", ColumnType.INT).col("a", ColumnType.INT).col("b", ColumnType.INT));
    }

    @Test
    public void testJoin2() throws Exception {
        assertModel(
                "select-choose x from (((select-choose tab2.x x from (tab join tab2 on tab2.x = tab.x)) t join tab3 on tab3.x = t.x))",
                "select x from ((select tab2.x from tab join tab2 on tab.x=tab2.x) t join tab3 on tab3.x = t.x)",
                modelOf("tab").col("x", ColumnType.INT),
                modelOf("tab2").col("x", ColumnType.INT),
                modelOf("tab3").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testJoinColumnAlias() {
        assertSyntaxError(
                "(select c.customerId, o.customerId kk, count() from customers c" +
                        " outer join orders o on c.customerId = o.customerId) " +
                        " where kk = NaN limit 10",
                123,
                "Invalid column",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testJoinColumnResolutionOnSubQuery() throws ParserException {
        assertModel(
                "select-group-by sum(timestamp) sum from ((y) _xQdbA0 cross join (x))",
                "select sum(timestamp) from (y) cross join (x)",
                modelOf("x").col("ccy", ColumnType.SYMBOL),
                modelOf("y").col("ccy", ColumnType.SYMBOL).col("timestamp", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testJoinColumnResolutionOnSubQuery2() throws ParserException {
        assertModel(
                "select-group-by sum(timestamp) sum from ((y) _xQdbA0 join (x) _xQdbA1 on _xQdbA1.ccy = _xQdbA0.ccy and _xQdbA1.sym = _xQdbA0.sym)",
                "select sum(timestamp) from (y) join (x) on (ccy, sym)",
                modelOf("x").col("ccy", ColumnType.SYMBOL).col("sym", ColumnType.INT),
                modelOf("y").col("ccy", ColumnType.SYMBOL).col("timestamp", ColumnType.TIMESTAMP).col("sym", ColumnType.INT)
        );
    }

    @Test
    public void testJoinColumnResolutionOnSubQuery3() throws ParserException {
        assertModel(
                "select-group-by sum(timestamp) sum from ((y) _xQdbA0 cross join x)",
                "select sum(timestamp) from (y) cross join x",
                modelOf("x").col("ccy", ColumnType.SYMBOL),
                modelOf("y").col("ccy", ColumnType.SYMBOL).col("timestamp", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testJoinCycle() throws Exception {
        assertModel(
                "orders" +
                        " join customers on customers.customerId = orders.customerId" +
                        " join (orderDetails d where d.orderId = d.productId) d on d.productId = orders.orderId" +
                        " join suppliers on suppliers.supplier = orders.orderId" +
                        " join products on products.productId = orders.orderId and products.supplier = suppliers.supplier",
                "orders" +
                        " join customers on orders.customerId = customers.customerId" +
                        " join orderDetails d on d.orderId = orders.orderId and orders.orderId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " join products on d.productId = products.productId and orders.orderId = products.productId" +
                        " where orders.orderId = suppliers.supplier",
                modelOf("orders").col("customerId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.SYMBOL),
                modelOf("suppliers").col("supplier", ColumnType.SYMBOL)

        );
    }

    @Test
    public void testJoinGroupBy() throws Exception {
        assertModel("select-group-by" +
                        " country," +
                        " avg(quantity) avg " +
                        "from (orders o join (customers c where country ~ '^Z') c on c.customerId = o.customerId join orderDetails d on d.orderId = o.orderId)",
                "select country, avg(quantity) from orders o " +
                        "join customers c on c.customerId = o.customerId " +
                        "join orderDetails d on o.orderId = d.orderId" +
                        " where country ~ '^Z'",
                modelOf("orders").col("customerId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT).col("country", ColumnType.SYMBOL),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("quantity", ColumnType.DOUBLE)
        );
    }

    @Test
    public void testJoinGroupByFilter() throws Exception {
        assertModel(
                "(select-group-by" +
                        " country," +
                        " avg(quantity) avg " +
                        "from" +
                        " (orders o" +
                        " join (customers c where country ~ '^Z') c on c.customerId = o.customerId" +
                        " join orderDetails d on d.orderId = o.orderId)) " +
                        "where avg > 2",
                "(select country, avg(quantity) avg from orders o " +
                        "join customers c on c.customerId = o.customerId " +
                        "join orderDetails d on o.orderId = d.orderId" +
                        " where country ~ '^Z') where avg > 2",
                modelOf("orders").col("customerId", ColumnType.INT).col("orderId", ColumnType.INT).col("quantity", ColumnType.DOUBLE),
                modelOf("customers").col("customerId", ColumnType.INT).col("country", ColumnType.SYMBOL),
                modelOf("orderDetails").col("orderId", ColumnType.INT)
        );
    }

    @Test
    public void testJoinImpliedCrosses() throws Exception {
        assertModel(
                "orders cross" +
                        " join products" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " cross join customers" +
                        " cross join orderDetails d" +
                        " const-where 1 = 1 and 2 = 2 and 3 = 3",
                "orders" +
                        " join customers on 1=1" +
                        " join orderDetails d on 2=2" +
                        " join products on 3=3" +
                        " join suppliers on products.supplier = suppliers.supplier",
                modelOf("orders").col("customerId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.SYMBOL),
                modelOf("suppliers").col("supplier", ColumnType.SYMBOL)
        );
    }

    @Test
    public void testJoinMultipleFields() throws Exception {
        assertModel(
                "orders" +
                        " join customers on customers.customerId = orders.customerId" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.productId = customers.customerId and d.orderId = orders.orderId" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier",
                "orders" +
                        " join customers on orders.customerId = customers.customerId" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = customers.customerId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("customerId", ColumnType.INT).col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.SYMBOL),
                modelOf("suppliers").col("supplier", ColumnType.SYMBOL)
        );
    }

    @Test
    public void testJoinOnColumns() throws ParserException {
        assertModel(
                "select-choose a.x x, b.y y from (tab1 a join tab2 b on b.z = a.z)",
                "select a.x, b.y from tab1 a join tab2 b on (z)",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT),
                modelOf("tab2")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
                        .col("s", ColumnType.INT)
        );
    }

    @Test
    public void testJoinOneFieldToTwoAcross2() throws Exception {
        assertModel(
                "orders" +
                        " join customers on customers.customerId = orders.orderId" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.orderId = orders.orderId" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " where orders.customerId = orders.orderId",
                "orders" +
                        " join customers on orders.customerId = customers.customerId" +
                        " join orderDetails d on d.orderId = customers.customerId and orders.orderId = d.orderId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT)
        );
    }

    @Test
    public void testJoinOneFieldToTwoReorder() throws Exception {
        assertModel(
                "orders" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.orderId = orders.customerId" +
                        " join customers on customers.customerId = orders.customerId" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " where orders.orderId = orders.customerId",
                "orders" +
                        " join orderDetails d on d.orderId = orders.orderId and d.orderId = customers.customerId" +
                        " join customers on orders.customerId = customers.customerId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("orderId", ColumnType.INT).col("customerId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT)
        );
    }

    @Test
    public void testJoinOrder4() throws ParserException {
        assertModel(
                "a" +
                        " cross join b" +
                        " asof join d" +
                        " join e on e.id = b.id" +
                        " cross join c",
                "a" +
                        " cross join b cross join c" +
                        " asof join d inner join e on b.id = e.id",
                modelOf("a"),
                modelOf("b").col("id", ColumnType.INT),
                modelOf("c"),
                modelOf("d"),
                modelOf("e").col("id", ColumnType.INT)
        );
    }

    @Test
    public void testJoinReorder() throws Exception {
        assertModel(
                "orders" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.orderId = orders.orderId" +
                        " join customers on customers.customerId = d.productId" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " const-where 1 = 1",
                "orders" +
                        " join customers on 1=1" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = customers.customerId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT)
        );
    }

    @Test
    public void testJoinReorder3() throws Exception {
        assertModel(
                "orders" +
                        " join shippers on shippers.shipper = orders.orderId" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.productId = shippers.shipper" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " cross join customers" +
                        " const-where 1 = 1",
                "orders" +
                        " outer join customers on 1=1" +
                        " join shippers on shippers.shipper = orders.orderId" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = shippers.shipper" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " join products on d.productId = products.productId" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT),
                modelOf("shippers").col("shipper", ColumnType.INT)
        );
    }

    @Test
    public void testJoinReorderRoot() throws Exception {
        assertModel(
                "customers" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.productId = customers.customerId" +
                        " join orders on orders.orderId = d.orderId join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier",
                "customers" +
                        " cross join orders" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = customers.customerId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",

                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT)
        );
    }

    @Test
    public void testJoinReorderRoot2() throws Exception {
        assertModel(
                "orders" +
                        " join shippers on shippers.shipper = orders.orderId" +
                        // joining on productId = shipper is sufficient because:
                        // 1. shipper = orders.orderId
                        // 2. d.orderId = orders.orderId
                        // 3. d.productId = shipper
                        " join (orderDetails d where d.productId = d.orderId) d on d.productId = shippers.shipper" +
                        " join products on products.productId = d.productId" +
                        " join suppliers on suppliers.supplier = products.supplier" +
                        " cross join customers" +
                        " const-where 1 = 1",
                "orders" +
                        " outer join customers on 1=1" +
                        " join shippers on shippers.shipper = orders.orderId" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = shippers.shipper" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId",
                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails").col("orderId", ColumnType.INT).col("productId", ColumnType.INT),
                modelOf("products").col("productId", ColumnType.INT).col("supplier", ColumnType.INT),
                modelOf("suppliers").col("supplier", ColumnType.INT),
                modelOf("shippers").col("shipper", ColumnType.INT)
        );
    }

    @Test
    public void testJoinSubQuery() throws Exception {
        assertModel(
                "orders" +
                        " join (select-choose customerId, customerName from (customers where customerName ~ 'X')) on customerName = orderId",
                "orders" +
                        " cross join (select customerId, customerName from customers where customerName ~ 'X')" +
                        " where orderId = customerName",
                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT).col("customerName", ColumnType.STRING)

        );
    }

    @Test
    public void testJoinSubQueryConstantWhere() throws Exception {
        assertModel(
                "select-choose o.customerId customerId" +
                        " from ((select-choose customerId cid from (customers where 100 = customerId)) c" +
                        " outer join (orders o where o.customerId = 100) o on o.customerId = c.cid" +
                        " const-where 10 = 9)",
                "select o.customerId from (select customerId cid from customers) c" +
                        " outer join orders o on c.cid = o.customerId" +
                        " where 100 = c.cid and 10=9",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testJoinSubQueryWherePosition() throws Exception {
        assertModel(
                "select-choose" +
                        " o.customerId customerId " +
                        "from " +
                        "((select-choose" +
                        " customerId cid " +
                        "from (customers where 100 = customerId)) c " +
                        "outer join (orders o where o.customerId = 100) o on o.customerId = c.cid)",
                "select o.customerId from (select customerId cid from customers) c" +
                        " outer join orders o on c.cid = o.customerId" +
                        " where 100 = c.cid",
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orders").col("customerId", ColumnType.INT)
        );
    }

    @Test
    public void testJoinWithFilter() throws Exception {
        assertModel(
                "customers" +
                        " join (orderDetails d where d.productId = d.orderId) d on d.productId = customers.customerId" +
                        " join orders on orders.orderId = d.orderId post-join-where d.quantity < orders.orderId" +
                        " join products on products.productId = d.productId post-join-where products.price > d.quantity or d.orderId = orders.orderId" +
                        " join suppliers on suppliers.supplier = products.supplier",
                "customers" +
                        " cross join orders" +
                        " join orderDetails d on d.orderId = orders.orderId and d.productId = customers.customerId" +
                        " join products on d.productId = products.productId" +
                        " join suppliers on products.supplier = suppliers.supplier" +
                        " where d.productId = d.orderId" +
                        " and (products.price > d.quantity or d.orderId = orders.orderId) and d.quantity < orders.orderId",

                modelOf("orders").col("orderId", ColumnType.INT),
                modelOf("customers").col("customerId", ColumnType.INT),
                modelOf("orderDetails")
                        .col("orderId", ColumnType.INT)
                        .col("productId", ColumnType.INT)
                        .col("quantity", ColumnType.DOUBLE),
                modelOf("products").col("productId", ColumnType.INT)
                        .col("supplier", ColumnType.INT)
                        .col("price", ColumnType.DOUBLE),
                modelOf("suppliers").col("supplier", ColumnType.INT)
        );
    }

    @Test
    public void testLexerReset() {

        for (int i = 0; i < 10; i++) {
            try {
                parser.parse("select \n" +
                        "-- ltod(Date)\n" +
                        "count() \n" +
                        "-- from acc\n" +
                        "from acc(Date) sample by 1d\n" +
                        "-- where x = 10\n");
                Assert.fail();
            } catch (ParserException e) {
                TestUtils.assertEquals("Unexpected token: Date", e.getFlyweightMessage());
            }
        }
    }

    @Test
    public void testMissingWhere() {
        try {
            parser.parse("select id, x + 10, x from tab id ~ 'HBRO'");
            Assert.fail("Exception expected");
        } catch (ParserException e) {
            Assert.assertEquals(33, e.getPosition());
        }
    }

    @Test
    public void testMixedFieldsSubQuery() throws Exception {
        assertModel(
                "select-choose x, y from ((select-virtual x, z + x y from (tab t2 latest by x where x > 100)) t1 where y > 0)",
                "select x, y from (select x,z + x y from tab t2 latest by x where x > 100) t1 where y > 0",
                modelOf("tab").col("x", ColumnType.INT).col("z", ColumnType.INT));
    }

    @Test
    public void testMostRecentWhereClause() throws Exception {
        assertModel(
                "select-virtual x, sum + 25 ohoh from (select-group-by x, sum(z) sum from (select-virtual a + b * c x, z from (zyzy latest by x where in(y,x,a) and b = 10)))",
                "select a+b*c x, sum(z)+25 ohoh from zyzy latest by x where a in (x,y) and b = 10",
                modelOf("zyzy")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("c", ColumnType.INT)
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    @Test
    public void testMultipleExpressions() throws Exception {
        assertModel(
                "select-virtual x, sum + 25 ohoh from (select-group-by x, sum(z) sum from (select-virtual a + b * c x, z from (zyzy)))",
                "select a+b*c x, sum(z)+25 ohoh from zyzy",
                modelOf("zyzy")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("c", ColumnType.INT)
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    @Test
    public void testOneAnalyticColumn() throws Exception {
        assertModel(
                "select-analytic a, b, f(c) f over (partition by b order by ts) from (xyz)",
                "select a,b, f(c) over (partition by b order by ts) from xyz",
                modelOf("xyz")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("c", ColumnType.INT)
        );
    }

    @Test
    public void testOptimiseNotAnd() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a != b or b != a)",
                "select a, b from tab where not (a = b and b = a)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotEqual() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a != b)",
                "select a, b from tab where not (a = b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotGreater() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a <= b)",
                "select a, b from tab where not (a > b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotGreaterOrEqual() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a < b)",
                "select a, b from tab where not (a >= b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotLess() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a >= b)",
                "select a, b from tab where not (a < b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotLessOrEqual() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a > b)",
                "select a, b from tab where not (a <= b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotLiteral() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where not(a))",
                "select a, b from tab where not (a)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotLiteralOr() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where not(a) and b != a)",
                "select a, b from tab where not (a or b = a)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotNotEqual() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a = b)",
                "select a, b from tab where not (a != b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotNotNotEqual() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a != b)",
                "select a, b from tab where not(not (a != b))",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotOr() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where a != b and b != a)",
                "select a, b from tab where not (a = b or b = a)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptimiseNotOrLiterals() throws ParserException {
        assertModel(
                "select-choose a, b from (tab where not(a) and not(b))",
                "select a, b from tab where not (a or b)",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT));
    }

    @Test
    public void testOptionalSelect() throws Exception {
        assertModel(
                "tab t2 latest by x where x > 100",
                "tab t2 latest by x where x > 100",
                modelOf("tab").col("x", ColumnType.INT));
    }

    @Test
    public void testOrderBy1() throws Exception {
        assertModel(
                "select-choose x, y from (select-choose x, y, z from (tab) order by x, y, z)",
                "select x,y from tab order by x,y,z",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );

    }

    @Test
    public void testOrderByAmbiguousColumn() {
        assertSyntaxError(
                "select tab1.x from tab1 join tab2 on (x) order by y",
                50,
                "Ambiguous",
                modelOf("tab1").col("x", ColumnType.INT).col("y", ColumnType.INT),
                modelOf("tab2").col("x", ColumnType.INT).col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByExpression() {
        assertSyntaxError("select x, y from tab order by x+y", 31, "Unexpected");
    }

    @Test
    public void testOrderByGroupByCol() throws ParserException {
        assertModel(
                "select-group-by a, sum(b) b from (tab) order by b",
                "select a, sum(b) b from tab order by b",
                modelOf("tab").col("a", ColumnType.INT).col("b", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByGroupByColPrefixed() throws ParserException {
        assertModel(
                "select-group-by a, sum(b) b from (tab)",
                "select a, sum(b) b from tab order by tab.b, a",
                modelOf("tab").col("a", ColumnType.INT).col("b", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByGroupByColPrefixed2() throws ParserException {
        assertModel(
                "select-group-by a, sum(b) b from (tab) order by a",
                "select a, sum(b) b from tab order by a, tab.b",
                modelOf("tab").col("a", ColumnType.INT).col("b", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByGroupByColPrefixed3() throws ParserException {
        assertModel(
                "select-group-by a, sum(b) b from (tab) order by a",
                "select a, sum(b) b from tab order by tab.a, tab.b",
                modelOf("tab").col("a", ColumnType.INT).col("b", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnAliasedColumn() throws ParserException {
        assertModel(
                "select-choose y from (select-choose y, tab.x x from (tab) order by x)",
                "select y from tab order by tab.x",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnExpression() throws ParserException {
        assertModel(
                "select-virtual y + x z from (tab) order by z",
                "select y+x z from tab order by z",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnJoinSubQuery() throws ParserException {
        assertModel(
                "select-choose x, y from (select-choose a.x x, b.y y, b.s s from ((select-choose x from (tab1 where x = 'Z')) a join (tab2 where s ~ 'K') b on b.z = a.z) order by s)",
                "select a.x, b.y from (select x from tab1 where x = 'Z' order by x) a join (tab2 where s ~ 'K') b on a.z=b.z order by b.s",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT),
                modelOf("tab2")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
                        .col("s", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnJoinSubQuery2() throws ParserException {
        assertModel(
                "select-choose a.x x, b.y y from ((select-choose x from (select-choose x, z from (tab1 where x = 'Z') order by z)) a join (tab2 where s ~ 'K') b on b.z = a.z)",
                "select a.x, b.y from (select x from tab1 where x = 'Z' order by z) a join (tab2 where s ~ 'K') b on a.z=b.z",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT),
                modelOf("tab2")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
                        .col("s", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnJoinSubQuery3() throws ParserException {
        assertModel(
                "select-choose a.x x, b.y y from ((select-choose x from (select-choose x, z from (tab1 where x = 'Z') order by z)) a asof join (select-choose y, z from (select-choose y, z, s from (tab2 where s ~ 'K') order by s)) b on b.z = a.x)",
                "select a.x, b.y from (select x from tab1 where x = 'Z' order by z) a asof join (select y,z from tab2 where s ~ 'K' order by s) b where a.x = b.z",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT),
                modelOf("tab2")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
                        .col("s", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnJoinTableReference() throws ParserException {
        assertModel(
                "select-choose x, y from (select-choose a.x x, b.y y, b.s s from (tab1 a join tab2 b on b.z = a.z) order by s)",
                "select a.x, b.y from tab1 a join tab2 b on a.z = b.z order by b.s",
                modelOf("tab1")
                        .col("x", ColumnType.INT)
                        .col("z", ColumnType.INT),
                modelOf("tab2")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
                        .col("s", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnNonSelectedColumn() throws ParserException {
        assertModel(
                "select-choose y from (select-choose y, x from (tab) order by x)",
                "select y from tab order by x",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnNonSelectedColumn2() throws ParserException {
        assertModel(
                "select-choose column from (select-virtual 2 * y + x column, x from (select-choose 2 * y + x column, x from (tab)) order by x)",
                "select 2*y+x from tab order by x",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnNonSelectedColumn3() throws ParserException {
        assertModel(
                "select-choose" +
                        " column," +
                        " column1" +
                        " from (" +
                        "select-virtual" +
                        " 2 * y + x column," +
                        " 3 / x column1, x" +
                        " from (" +
                        "select-choose" +
                        " 2 * y + x column," +
                        " 3 / x column1," +
                        " x from (tab)) order by x)",
                "select 2*y+x, 3/x from tab order by x",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnOuterResult() throws ParserException {
        assertModel(
                "select-virtual x, sum1 + sum z from (select-group-by x, sum(3 / x) sum, sum(2 * y + x) sum1 from (tab)) order by z",
                "select x, sum(2*y+x) + sum(3/x) z from tab order by z asc, tab.y desc",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByOnSelectedAlias() throws ParserException {
        assertModel(
                "select-choose y z from (tab) order by z",
                "select y z from tab order by z",
                modelOf("tab")
                        .col("x", ColumnType.DOUBLE)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testOrderByWithSampleBy() throws ParserException {
        assertModel(
                "select-group-by t, a, sum(b) sum from ((tab order by t)) timestamp (t) sample by 2m order by a",
                "select a, sum(b) from (tab order by t) timestamp(t) sample by 2m order by a",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("t", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testOrderByWithSampleBy2() throws ParserException {
        // todo: need another test to assert exception when sample by clause present without aggregation
        assertModel(
                "select-group-by a, sum(b) sum from (((tab order by t) timestamp (t) sample by 10m)) order by a",
                "select a, sum(b) from ((tab order by t) timestamp(t) sample by 10m order by t)order by a",
                modelOf("tab")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("t", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testOuterJoin() throws Exception {
        assertModel(
                "select-choose a.x x from (a a outer join b on b.x = a.x)",
                "select a.x from a a outer join b on b.x = a.x",
                modelOf("a").col("x", ColumnType.INT),
                modelOf("b").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testSampleBy() throws Exception {
        assertModel(
                "select-group-by timestamp, x, avg(y) avg from (tab) timestamp (timestamp) sample by 2m",
                "select x,avg(y) from tab sample by 2m",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .timestamp()
        );
    }

    @Test
    public void testSampleByAlreadySelected() throws Exception {
        assertModel(
                "select-group-by x, x1, avg(y) avg from (select-choose x, x x1, y from (tab)) timestamp (x) sample by 2m",
                "select x,avg(y) from tab timestamp(x) sample by 2m",
                modelOf("tab")
                        .col("x", ColumnType.TIMESTAMP)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testSampleByAltTimestamp() throws Exception {
        assertModel(
                "select-group-by t, x, avg(y) avg from (tab) timestamp (t) sample by 2m",
                "select x,avg(y) from tab timestamp(t) sample by 2m",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("t", ColumnType.TIMESTAMP)
        );
    }

    @Test
    public void testSampleByInvalidColumn() {
        assertSyntaxError("select x,sum(y) from tab timestamp(z) sample by 2m",
                35,
                "Invalid column",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .timestamp()
        );
    }

    @Test
    public void testSampleByInvalidType() {
        assertSyntaxError("select x,sum(y) from tab timestamp(x) sample by 2m",
                35,
                "not a TIMESTAMP",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .timestamp()
        );
    }

    @Test
    public void testSampleByNoAggregate() {
        assertSyntaxError("select x,y from tab sample by 2m", 30, "at least one",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .timestamp()
        );
    }

    @Test
    public void testSampleByUndefinedTimestamp() {
        assertSyntaxError("select x,sum(y) from tab sample by 2m",
                35,
                "TIMESTAMP column is not defined",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
        );
    }

    @Test
    public void testSelectAliasAsFunction() {
        assertSyntaxError(
                "select sum(x) x() from tab",
                15,
                ",|from expected",
                modelOf("tab").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testSelectAnalyticOperator() {
        assertSyntaxError(
                "select sum(x), 2*x over() from tab",
                16,
                "Analytic function expected",
                modelOf("tab").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testSelectGroupByAndAnalytic() {
        assertSyntaxError(
                "select sum(x), count() over() from tab",
                0,
                "Analytic function is not allowed",
                modelOf("tab").col("x", ColumnType.INT)
        );
    }

    @Test
    public void testSelectOnItsOwn() {
        assertSyntaxError("select ", 6, "missing column");
    }

    @Test
    public void testSelectPlainColumns() throws Exception {
        assertModel(
                "select-choose a, b, c from (t)",
                "select a,b,c from t",
                modelOf("t").col("a", ColumnType.INT).col("b", ColumnType.INT).col("c", ColumnType.INT)
        );
    }

    @Test
    public void testSelectSingleExpression() throws Exception {
        assertModel(
                "select-virtual a + b * c x from (t)",
                "select a+b*c x from t",
                modelOf("t").col("a", ColumnType.INT).col("b", ColumnType.INT).col("c", ColumnType.INT));
    }

    @Test
    public void testSimpleSubQuery() throws Exception {
        assertModel(
                "(x where y > 1)",
                "(x) where y > 1",
                modelOf("x").col("y", ColumnType.INT)
        );
    }

    @Test
    public void testSingleTableLimit() throws Exception {
        assertModel(
                "select-choose x, y from (tab where x > z limit 100)",
                "select x x, y y from tab where x > z limit 100",
                modelOf("tab")
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    @Test
    public void testSingleTableLimitLoHi() throws Exception {
        assertModel(
                "select-choose x, y from (tab where x > z limit 100,200)",
                "select x x, y y from tab where x > z limit 100,200",
                modelOf("tab").col("x", ColumnType.INT).col("y", ColumnType.INT).col("z", ColumnType.INT)
        );
    }

    @Test
    public void testSingleTableLimitLoHiExtraToken() {
        assertSyntaxError("select x x, y y from tab where x > z limit 100,200 b", 51, "Unexpected");
    }

    @Test
    public void testSingleTableNoWhereLimit() throws Exception {
        assertModel(
                "select-choose x, y from (tab limit 100)",
                "select x x, y y from tab limit 100",
                modelOf("tab").col("x", ColumnType.INT).col("y", ColumnType.INT));
    }

    @Test
    public void testSubQuery() throws Exception {
        assertModel(
                "select-choose x, y from ((select-choose x, y from (tab t2 latest by x where x > 100 and y > 0)) t1)",
                "select x, y from (select x, y from tab t2 latest by x where x > 100) t1 where y > 0",
                modelOf("tab").col("x", ColumnType.INT).col("y", ColumnType.INT)
        );
    }

    @Test
    public void testSubQueryAliasWithSpace() throws Exception {
        assertModel(
                "(x where a > 1 and x > 1) 'b a'",
                "(x where a > 1) 'b a' where x > 1",
                modelOf("x")
                        .col("x", ColumnType.INT)
                        .col("a", ColumnType.INT));
    }

    @Test
    public void testSubQueryLimitLoHi() throws Exception {
        assertModel(
                "(select-choose x, y from (tab where x > z and x = y limit 100,200)) limit 150",
                "(select x x, y y from tab where x > z limit 100,200) where x = y limit 150",
                modelOf("tab").col("x", ColumnType.INT).col("y", ColumnType.INT).col("z", ColumnType.INT)
        );
    }

    @Test
    public void testTimestampOnSubQuery() throws Exception {
        assertModel("select-choose x from ((a b where x > y) timestamp (x))",
                "select x from (a b) timestamp(x) where x > y",
                modelOf("a").col("x", ColumnType.INT).col("y", ColumnType.INT));
    }

    @Test
    public void testTimestampOnTable() throws Exception {
        assertModel(
                "select-choose x from (a b timestamp (x) where x > y)",
                "select x from a b timestamp(x) where x > y",
                modelOf("a")
                        .col("x", ColumnType.TIMESTAMP)
                        .col("y", ColumnType.TIMESTAMP));
    }

    @Test
    public void testTooManyColumnsEdgeInOrderBy() throws Exception {
        try (TableModel model = new TableModel(configuration, "x", PartitionBy.NONE)) {
            for (int i = 0; i < SqlLexerOptimiser.MAX_ORDER_BY_COLUMNS - 1; i++) {
                model.col("f" + i, ColumnType.INT);
            }
            CairoTestUtils.create(model);
        }

        StringBuilder b = new StringBuilder();
        b.append("x order by ");
        for (int i = 0; i < SqlLexerOptimiser.MAX_ORDER_BY_COLUMNS - 1; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('f').append(i);
        }
        QueryModel st = (QueryModel) parser.parse(b);
        Assert.assertEquals(SqlLexerOptimiser.MAX_ORDER_BY_COLUMNS - 1, st.getOrderBy().size());
    }

    @Test
    public void testTooManyColumnsInOrderBy() {
        StringBuilder b = new StringBuilder();
        b.append("x order by ");
        for (int i = 0; i < SqlLexerOptimiser.MAX_ORDER_BY_COLUMNS; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('f').append(i);
        }
        try {
            parser.parse(b);
        } catch (ParserException e) {
            TestUtils.assertEquals("Too many columns", e.getFlyweightMessage());
        }
    }

    @Test
    public void testTwoAnalyticColumns() throws Exception {
        assertModel(
                "select-analytic a, b, f(c) my over (partition by b order by ts), d(c) d over () from (xyz)",
                "select a,b, f(c) my over (partition by b order by ts), d(c) over() from xyz",
                modelOf("xyz").col("c", ColumnType.INT).col("b", ColumnType.INT).col("a", ColumnType.INT)
        );
    }

    @Test
    public void testUnbalancedBracketInSubQuery() {
        assertSyntaxError("select x from (tab where x > 10 t1", 32, "expected");
    }

    @Test
    public void testUnderTerminatedOver() {
        assertSyntaxError("select a,b, f(c) my over (partition by b order by ts from xyz", 53, "expected");
    }

    @Test
    public void testUnderTerminatedOver2() {
        assertSyntaxError("select a,b, f(c) my over (partition by b order by ts", 50, "Unexpected");
    }

    @Test
    public void testUnexpectedTokenInAnalyticFunction() {
        assertSyntaxError("select a,b, f(c) my over (by b order by ts) from xyz", 26, "expected");
    }

    @Test
    public void testWhereClause() throws Exception {
        assertModel(
                "select-virtual x, sum + 25 ohoh from (select-group-by x, sum(z) sum from (select-virtual a + b * c x, z from (zyzy where in(10,0,a) and b = 10)))",
                "select a+b*c x, sum(z)+25 ohoh from zyzy where a in (0,10) and b = 10",
                modelOf("zyzy")
                        .col("a", ColumnType.INT)
                        .col("b", ColumnType.INT)
                        .col("c", ColumnType.INT)
                        .col("x", ColumnType.INT)
                        .col("y", ColumnType.INT)
                        .col("z", ColumnType.INT)
        );
    }

    private void assertModel(String expected, String query, TableModel... tableModels) throws ParserException {
        try {
            for (int i = 0, n = tableModels.length; i < n; i++) {
                CairoTestUtils.create(tableModels[i]);
            }
            sink.clear();
            ((QueryModel) parser.parse(query)).toSink(sink);
            TestUtils.assertEquals(expected, sink);
        } finally {
            Assert.assertTrue(engine.releaseAllReaders());
            for (int i = 0, n = tableModels.length; i < n; i++) {
                TableModel tableModel = tableModels[i];
                Path path = tableModel.getPath().of(tableModel.getCairoCfg().getRoot()).concat(tableModel.getName()).put(Files.SEPARATOR).$();
                Assert.assertTrue(configuration.getFilesFacade().rmdir(path));
                tableModel.close();
            }
        }
    }

    private void assertSyntaxError(String query, int position, String contains, TableModel... tableModels) {
        try {
            for (int i = 0, n = tableModels.length; i < n; i++) {
                CairoTestUtils.create(tableModels[i]);
            }
            parser.parse(query);
            Assert.fail("Exception expected");
        } catch (ParserException e) {
            Assert.assertEquals(position, e.getPosition());
            TestUtils.assertContains(e.getMessage(), contains);
        } finally {
            Assert.assertTrue(engine.releaseAllReaders());
            for (int i = 0, n = tableModels.length; i < n; i++) {
                TableModel tableModel = tableModels[i];
                Path path = tableModel.getPath().of(tableModel.getCairoCfg().getRoot()).concat(tableModel.getName()).put(Files.SEPARATOR).$();
                Assert.assertTrue(configuration.getFilesFacade().rmdir(path));
                tableModel.close();
            }
        }
    }

    private void assertTableModel(
            String sql,
            String[] expectedNames,
            int[] expectedTypes,
            String[] columnsWithIndexes,
            int[] expectedBlockSizes,
            int expectedTimestampIndex,
            String expectedPartitionBy) throws ParserException {

        ParsedModel model = parser.parse(sql);
        Assert.assertTrue(model instanceof CreateTableModel);

        final CreateTableModel m = (CreateTableModel) model;

        Assert.assertEquals(expectedPartitionBy, m.getPartitionBy().token);
        Assert.assertEquals(expectedNames.length, m.getColumnCount());

        // check indexes
        HashSet<String> indexed = new HashSet<>();
        for (int i = 0, n = columnsWithIndexes.length; i < n; i++) {
            int index = m.getColumnIndex(columnsWithIndexes[i]);

            Assert.assertNotEquals(-1, index);
            Assert.assertTrue(m.getIndexedFlag(index));
            Assert.assertEquals(expectedBlockSizes[i], m.getIndexBlockCapacity(index));
            indexed.add(columnsWithIndexes[i]);
        }

        for (int i = 0, n = m.getColumnCount(); i < n; i++) {
            Assert.assertEquals(expectedNames[i], m.getColumnName(i));
            Assert.assertEquals(expectedTypes[i], m.getColumnType(i));
            // assert that non-indexed columns are correctly reflected in model
            if (!indexed.contains(expectedNames[i])) {
                Assert.assertFalse(m.getIndexedFlag(i));
                Assert.assertEquals(0, m.getIndexBlockCapacity(i));
            }
        }
        Assert.assertEquals(expectedTimestampIndex, m.getColumnIndex(m.getTimestamp().token));
    }

    private TableModel modelOf(String tableName) {
        return new TableModel(configuration, tableName, PartitionBy.NONE);
    }
}
