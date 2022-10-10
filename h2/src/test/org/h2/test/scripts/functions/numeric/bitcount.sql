-- Copyright 2004-2022 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (https://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

SELECT V, BITCOUNT(V) C FROM (VALUES 0, 10, -1) T(V);
> V  C
> -- --
> -1 32
> 0  0
> 10 2
> rows: 3

EXPLAIN SELECT
    BITCOUNT(CAST((0xC5 - 0x100) AS TINYINT)),
    BITCOUNT(CAST(0xC5 AS SMALLINT)),
    BITCOUNT(CAST(0xC5 AS INTEGER)),
    BITCOUNT(CAST(0xC5 AS BIGINT)),
    BITCOUNT(CAST(X'C5' AS VARBINARY)),
    BITCOUNT(CAST(X'C5' AS BINARY));
>> SELECT CAST(4 AS BIGINT), CAST(4 AS BIGINT), CAST(4 AS BIGINT), CAST(4 AS BIGINT), CAST(4 AS BIGINT), CAST(4 AS BIGINT)

SELECT BITCOUNT(X'13');
>> 3

SELECT BITCOUNT(X'0123456789ABCDEF');
>> 32

SELECT BITCOUNT(X'0123456789ABCDEF 33');
>> 36

SELECT BITCOUNT(X'1111111111111111 3333333333333333 77');
>> 54
