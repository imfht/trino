/*
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
package io.trino.plugin.hive;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.testing.AbstractTestQueryFramework;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Sets.union;
import static io.trino.plugin.hive.S3Assert.s3Path;
import static io.trino.testing.DataProviders.cartesianProduct;
import static io.trino.testing.DataProviders.toDataProvider;
import static io.trino.testing.DataProviders.trueFalse;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class BaseS3AndGlueMetastoreTest
        extends AbstractTestQueryFramework
{
    private final String partitionByKeyword;
    private final String locationKeyword;

    protected final String bucketName;
    protected final String schemaName = "test_glue_s3_" + randomNameSuffix();

    protected HiveMetastore metastore;
    protected AmazonS3 s3;

    protected BaseS3AndGlueMetastoreTest(String partitionByKeyword, String locationKeyword, String bucketName)
    {
        this.partitionByKeyword = requireNonNull(partitionByKeyword, "partitionByKeyword is null");
        this.locationKeyword = requireNonNull(locationKeyword, "locationKeyword is null");
        this.bucketName = requireNonNull(bucketName, "bucketName is null");
    }

    @BeforeClass
    public void setUp()
    {
        s3 = AmazonS3ClientBuilder.standard().build();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        if (metastore != null) {
            metastore.dropDatabase(schemaName, true);
            metastore = null;
        }
        if (s3 != null) {
            s3.shutdown();
            s3 = null;
        }
    }

    @DataProvider
    public Object[][] locationPatternsDataProvider()
    {
        return cartesianProduct(trueFalse(), locationPatterns().stream().collect(toDataProvider()));
    }

    protected List<String> locationPatterns()
    {
        return ImmutableList.<String>builder()
                .add("s3://%s/%s/regular/%s")
                .add("s3://%s/%s/trailing_slash/%s/")
                .add("s3://%s/%s//double_slash/%s")
                .add("s3://%s/%s/a%%percent/%s")
                .add("s3://%s/%s/a whitespace/%s")
                .add("s3://%s/%s/trailing_whitespace/%s ")
                .build();
    }

    @Test(dataProvider = "locationPatternsDataProvider")
    public void testBasicOperationsWithProvidedTableLocation(boolean partitioned, String locationPattern)
    {
        String tableName = "test_basic_operations_" + randomNameSuffix();
        String location = locationPattern.formatted(bucketName, schemaName, tableName);
        String partitionQueryPart = (partitioned ? "," + partitionByKeyword + " = ARRAY['col_str']" : "");

        assertUpdate("CREATE TABLE " + tableName + "(col_str, col_int)" +
                "WITH (location = '" + location + "'" + partitionQueryPart + ") " +
                "AS VALUES ('str1', 1), ('str2', 2), ('str3', 3)", 3);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('str2', 2), ('str3', 3)");
        validateTableLocation(tableName, location);

        assertUpdate("INSERT INTO " + tableName + " VALUES ('str4', 4)", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('str2', 2), ('str3', 3), ('str4', 4)");

        assertUpdate("UPDATE " + tableName + " SET col_str = 'other' WHERE col_int = 2", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('other', 2), ('str3', 3), ('str4', 4)");

        assertUpdate("DELETE FROM " + tableName + " WHERE col_int = 3", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('other', 2), ('str4', 4)");

        assertThat(getTableFiles(location)).isNotEmpty();
        validateDataFiles(partitioned ? "col_str" : "", tableName, location);
        validateMetadataFiles(location);

        assertUpdate("DROP TABLE " + tableName);
        validateFilesAfterDrop(location);
    }

    @Test(dataProvider = "locationPatternsDataProvider")
    public void testBasicOperationsWithProvidedSchemaLocation(boolean partitioned, String locationPattern)
    {
        String schemaName = "test_basic_operations_schema_" + randomNameSuffix();
        String schemaLocation = locationPattern.formatted(bucketName, schemaName, schemaName);
        String tableName = "test_basic_operations_table_" + randomNameSuffix();
        String qualifiedTableName = schemaName + "." + tableName;
        String partitionQueryPart = (partitioned ? "WITH (" + partitionByKeyword + " = ARRAY['col_str'])" : "");

        assertUpdate("CREATE SCHEMA " + schemaName + " WITH (location = '" + schemaLocation + "')");
        assertThat(getSchemaLocation(schemaName)).isEqualTo(schemaLocation);

        assertUpdate("CREATE TABLE " + qualifiedTableName + "(col_int int, col_str varchar)" + partitionQueryPart);
        // in case of regular CREATE TABLE, location has generated suffix
        String expectedTableLocationPattern = (schemaLocation.endsWith("/") ? schemaLocation : schemaLocation + "/") + tableName + "-[a-z0-9]+";
        String actualTableLocation = getTableLocation(qualifiedTableName);
        assertThat(actualTableLocation).matches(expectedTableLocationPattern);

        assertUpdate("INSERT INTO " + qualifiedTableName + " (col_str, col_int) VALUES ('str1', 1), ('str2', 2), ('str3', 3)", 3);
        assertQuery("SELECT col_str, col_int FROM " + qualifiedTableName, "VALUES ('str1', 1), ('str2', 2), ('str3', 3)");

        assertUpdate("UPDATE " + qualifiedTableName + " SET col_str = 'other' WHERE col_int = 2", 1);
        assertQuery("SELECT col_str, col_int FROM " + qualifiedTableName, "VALUES ('str1', 1), ('other', 2), ('str3', 3)");

        assertUpdate("DELETE FROM " + qualifiedTableName + " WHERE col_int = 3", 1);
        assertQuery("SELECT col_str, col_int FROM " + qualifiedTableName, "VALUES ('str1', 1), ('other', 2)");

        assertThat(getTableFiles(actualTableLocation)).isNotEmpty();
        validateDataFiles(partitioned ? "col_str" : "", qualifiedTableName, actualTableLocation);
        validateMetadataFiles(actualTableLocation);

        assertUpdate("DROP TABLE " + qualifiedTableName);
        assertThat(getTableFiles(actualTableLocation)).isEmpty();

        assertUpdate("DROP SCHEMA " + schemaName);
        assertThat(getTableFiles(actualTableLocation)).isEmpty();
    }

    @Test(dataProvider = "locationPatternsDataProvider")
    public void testMergeWithProvidedTableLocation(boolean partitioned, String locationPattern)
    {
        String tableName = "test_merge_" + randomNameSuffix();
        String location = locationPattern.formatted(bucketName, schemaName, tableName);
        String partitionQueryPart = (partitioned ? "," + partitionByKeyword + " = ARRAY['col_str']" : "");

        assertUpdate("CREATE TABLE " + tableName + "(col_str, col_int)" +
                "WITH (location = '" + location + "'" + partitionQueryPart + ") " +
                "AS VALUES ('str1', 1), ('str2', 2), ('str3', 3)", 3);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('str2', 2), ('str3', 3)");

        assertUpdate("MERGE INTO " + tableName + " USING (VALUES 1) t(x) ON false" +
                " WHEN NOT MATCHED THEN INSERT VALUES ('str4', 4)", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('str2', 2), ('str3', 3), ('str4', 4)");

        assertUpdate("MERGE INTO " + tableName + " USING (VALUES 2) t(x) ON col_int = x" +
                " WHEN MATCHED THEN UPDATE SET col_str = 'other'", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('other', 2), ('str3', 3), ('str4', 4)");

        assertUpdate("MERGE INTO " + tableName + " USING (VALUES 3) t(x) ON col_int = x" +
                " WHEN MATCHED THEN DELETE", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('str1', 1), ('other', 2), ('str4', 4)");

        assertThat(getTableFiles(location)).isNotEmpty();
        validateDataFiles(partitioned ? "col_str" : "", tableName, location);
        validateMetadataFiles(location);

        assertUpdate("DROP TABLE " + tableName);
        validateFilesAfterDrop(location);
    }

    @Test(dataProvider = "locationPatternsDataProvider")
    public void testOptimizeWithProvidedTableLocation(boolean partitioned, String locationPattern)
    {
        String tableName = "test_optimize_" + randomNameSuffix();
        String location = locationPattern.formatted(bucketName, schemaName, tableName);
        String partitionQueryPart = (partitioned ? "," + partitionByKeyword + " = ARRAY['value']" : "");
        String locationQueryPart = locationKeyword + "= '" + location + "'";

        assertUpdate("CREATE TABLE " + tableName + " (key integer, value varchar) " +
                "WITH (" + locationQueryPart + partitionQueryPart + ")");
        try {
            // create multiple data files, INSERT with multiple values would create only one file (if not partitioned)
            assertUpdate("INSERT INTO " + tableName + " VALUES (1, 'one')", 1);
            assertUpdate("INSERT INTO " + tableName + " VALUES (2, 'a//double_slash')", 1);
            assertUpdate("INSERT INTO " + tableName + " VALUES (3, 'a%percent')", 1);
            assertUpdate("INSERT INTO " + tableName + " VALUES (4, 'a//double_slash')", 1);
            assertUpdate("INSERT INTO " + tableName + " VALUES (11, 'one')", 1);

            Set<String> initialFiles = getActiveFiles(tableName);
            assertThat(initialFiles).hasSize(5);

            Session session = sessionForOptimize();
            computeActual(session, "ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");

            assertThat(query("SELECT sum(key), listagg(value, ' ') WITHIN GROUP (ORDER BY value) FROM " + tableName))
                    .matches("VALUES (BIGINT '21', VARCHAR 'a%percent a//double_slash a//double_slash one one')");

            Set<String> updatedFiles = getActiveFiles(tableName);
            validateFilesAfterOptimize(getTableLocation(tableName), initialFiles, updatedFiles);
        }
        finally {
            assertUpdate("DROP TABLE " + tableName);
        }
    }

    protected Session sessionForOptimize()
    {
        return getSession();
    }

    protected void validateFilesAfterOptimize(String location, Set<String> initialFiles, Set<String> updatedFiles)
    {
        assertThat(updatedFiles).hasSizeLessThan(initialFiles.size());
        assertThat(getAllDataFilesFromTableDirectory(location)).isEqualTo(union(initialFiles, updatedFiles));
    }

    protected abstract void validateDataFiles(String partitionColumn, String tableName, String location);

    protected abstract void validateMetadataFiles(String location);

    protected void validateTableLocation(String tableName, String location)
    {
        assertThat(getTableLocation(tableName)).isEqualTo(location);
    }

    protected void validateFilesAfterDrop(String location)
    {
        assertThat(getTableFiles(location)).isEmpty();
    }

    protected abstract Set<String> getAllDataFilesFromTableDirectory(String tableLocation);

    protected Set<String> getActiveFiles(String tableName)
    {
        return computeActual("SELECT \"$path\" FROM " + tableName).getOnlyColumnAsSet().stream()
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }

    protected String getTableLocation(String tableName)
    {
        return findLocationInQuery("SHOW CREATE TABLE " + tableName);
    }

    protected String getSchemaLocation(String schemaName)
    {
        return metastore.getDatabase(schemaName).orElseThrow(() -> new SchemaNotFoundException(schemaName))
                .getLocation().orElseThrow(() -> new IllegalArgumentException("Location is empty"));
    }

    private String findLocationInQuery(String query)
    {
        Pattern locationPattern = Pattern.compile(".*location = '(.*?)'.*", Pattern.DOTALL);
        Matcher m = locationPattern.matcher((String) computeActual(query).getOnlyValue());
        if (m.find()) {
            String location = m.group(1);
            verify(!m.find(), "Unexpected second match");
            return location;
        }
        throw new IllegalStateException("Location not found in" + query + " result");
    }

    protected List<String> getTableFiles(String location)
    {
        Matcher matcher = Pattern.compile("s3://[^/]+/(.+)").matcher(location);
        verify(matcher.matches(), "Does not match [%s]: [%s]", matcher.pattern(), location);
        String fileKey = matcher.group(1);
        ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucketName).withPrefix(fileKey);
        return s3.listObjectsV2(req).getObjectSummaries().stream()
                .map(S3ObjectSummary::getKey)
                .map(key -> format("s3://%s/%s", bucketName, key))
                .toList();
    }

    protected String schemaPath()
    {
        return "s3://%s/%s".formatted(bucketName, schemaName);
    }

    protected void verifyPathExist(String path)
    {
        assertThat(s3Path(s3, path)).exists();
    }
}
