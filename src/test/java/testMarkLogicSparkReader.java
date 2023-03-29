/*
 * Copyright 2023 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

public class testMarkLogicSparkReader {
    public static void main(String[] args) {
        System.out.println("Hello world!");
        SparkSession spark = SparkSession
                .builder()
                .appName("Java Spark  basic example")
                .config("spark.master", "local")
                .getOrCreate();

        Dataset<Row> df = spark.read().csv("src/test/resources/Hogwarts.csv");
        df.show();
    }
}
