/*
 * Copyright 2018 The CovenantSQL Authors.
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

package io.covenantsql.connector.example.jdbc;

import java.io.IOException;
import java.sql.*;
import java.util.Date;
import java.util.Properties;

public class Example {
    public static void main(String[] args) {
        try {
            Properties properties = new Properties();
            properties.load(Example.class.getResourceAsStream("../common-config.properties"));

            String host = System.getProperty("COVENANTSQL_HOST", "adp00.cn.gridb.io");
            String port = System.getProperty("COVENANTSQL_PORT", "7784");
            String database = System.getProperty("COVENANTSQL_DATABASE", "e1c4e80701773c1656a99d317148f2eada0fc6f2dad33afd5425e65bc9a35270");

            String url = String.format("jdbc:covenantsql://%s:%s/%s", host, port, database);
            System.out.printf("Build url: %s\n", url);

            System.out.println("Connecting to database...");
            Connection conn = DriverManager.getConnection(url, properties);

            System.out.println("Creating statement...");
            Statement stmt = conn.createStatement();
            String createTableSQL = "CREATE TABLE IF NOT EXISTS `users` (\n" +
                "            `id` INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                "            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "            `email` VARCHAR(255) NOT NULL,\n" +
                "            `password` VARCHAR(255) NOT NULL\n" +
                "        )";
            stmt.executeUpdate(createTableSQL);
            stmt.close();

            stmt = conn.createStatement();
            String insertSQL = "INSERT INTO `users` (`email`, `password`) VALUES ('Apple', 'appleisdelicious')";
            stmt.executeUpdate(insertSQL);
            stmt.close();

            stmt = conn.createStatement();
            String query = "SELECT * FROM `users`";
            ResultSet rs = stmt.executeQuery(query);

            while (rs.next()) {
                int id = rs.getInt("id");
                Date time = rs.getTime("created_at");
                String email = rs.getString("email");
                String password = rs.getString("password");

                System.out.print("ID: " + id);
                System.out.print(", Email: " + email);
                System.out.print(", Password: " + password);
                System.out.println(", Time: " + time);
            }

            rs.close();
            stmt.close();
            conn.close();
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }
}
