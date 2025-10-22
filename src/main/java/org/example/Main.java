package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URL;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;

public class Main {

    public static void main(String[] args) throws Exception {
//        LK_RECEIPT = вывод из оборота
//        SETS_AGGREGATION = формирование набора
        if (args.length < 2) {
            System.out.println("Использование: java -jar crypto.jar <inputFile> <certSerial> <typeDocument>");
            return;
        }

        String inputFile = args[0];
        String certSerial = args[1];
        String typeDocument = args[2];

        String baseUrl = "https://markirovka.sandbox.crptech.ru/api/v3/true-api";
        String urlAuthKey = baseUrl + "/auth/key";
        String urlAuthSignIn = baseUrl + "/auth/simpleSignIn";
        String urlCreateDoc = baseUrl + "/lk/documents/create?pg=petfood";

        // --- 1. Получаем данные для подписи (uuid + data) ---
        System.out.println("🔹 Запрашиваем данные для подписи...");
        URL authKeyUrl = new URL(urlAuthKey);
        HttpsURLConnection authKeyConn = (HttpsURLConnection) authKeyUrl.openConnection();
        authKeyConn.setRequestMethod("GET");
        authKeyConn.setRequestProperty("accept", "application/json");

        int authKeyCode = authKeyConn.getResponseCode();
        if (authKeyCode != 200) {
            System.err.println("Ошибка при получении auth/key, код: " + authKeyCode);
            printStream(authKeyConn.getErrorStream());
            return;
        }

        String authKeyResponse = readStream(authKeyConn.getInputStream());
        System.out.println("Ответ /auth/key: " + authKeyResponse);

        String uuid = extractJsonValue(authKeyResponse, "uuid");
        String data = extractJsonValue(authKeyResponse, "data");

        if (uuid == null || data == null) {
            System.err.println("Ошибка: не удалось извлечь uuid или data из ответа.");
            return;
        }

        // --- 2. Подписываем data через csptest ---
        System.out.println("🔹 Подписываем данные УКЭП...");

        File tempIn = File.createTempFile("data", ".txt");
        File tempOut = File.createTempFile("sign", ".txt");
        Files.write(tempIn.toPath(), data.getBytes(StandardCharsets.UTF_8));

        ProcessBuilder pb = new ProcessBuilder(
                "/opt/cprocsp/bin/amd64/csptest",
                "-sfsign", "-sign",
                "-in", tempIn.getAbsolutePath(),
                "-out", tempOut.getAbsolutePath(),
                "-my", certSerial,
                "-base64",
                "-add"
        );

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("Ошибка подписи, код выхода: " + exitCode);
            printStream(process.getErrorStream());
            return;
        }

        String signatureBase64 = new String(Files.readAllBytes(tempOut.toPath()), StandardCharsets.UTF_8)
                .replaceAll("\\s+", "");

        tempIn.delete();
        tempOut.delete();

        // --- 3. Отправляем /auth/simpleSignIn ---
        System.out.println("🔹 Отправляем подписанные данные для получения токена...");

        String authBody = String.format(
                "{ \"uuid\":\"%s\", \"data\":\"%s\" }",
                uuid, signatureBase64
        );

        URL authSignUrl = new URL(urlAuthSignIn);
        HttpsURLConnection authConn = (HttpsURLConnection) authSignUrl.openConnection();
        authConn.setRequestMethod("POST");
        authConn.setRequestProperty("accept", "application/json");
        authConn.setRequestProperty("Content-Type", "application/json");
        authConn.setDoOutput(true);
        try (OutputStream os = authConn.getOutputStream()) {
            os.write(authBody.getBytes(StandardCharsets.UTF_8));
        }

        int authCode = authConn.getResponseCode();
        String authResponse = readStream(authCode >= 400 ? authConn.getErrorStream() : authConn.getInputStream());
        System.out.println("Ответ /auth/simpleSignIn: " + authResponse);

        if (authCode != 200) {
            System.err.println("Ошибка при аутентификации. Код: " + authCode);
            return;
        }

        String bearerToken = extractJsonValue(authResponse, "token");
        if (bearerToken == null) {
            System.err.println("Ошибка: токен не найден в ответе.");
            return;
        }

        System.out.println("✅ Токен успешно получен.");

        // --- 4. Читаем файл с документом ---
        byte[] inputBytes = Files.readAllBytes(new File(inputFile).toPath());
        String compactJson = new String(inputBytes, StandardCharsets.UTF_8).replaceAll("\\s+", "");
        String base64Document = Base64.getEncoder().encodeToString(compactJson.getBytes(StandardCharsets.UTF_8));

        // --- Подписываем сам документ ---
        System.out.println("🔹 Подписываем документ для отправки...");

        File docIn = File.createTempFile("doc", ".txt");
        File docOut = File.createTempFile("signDoc", ".txt");
        Files.write(docIn.toPath(), compactJson.getBytes(StandardCharsets.UTF_8));

        ProcessBuilder pbDoc = new ProcessBuilder(
                "/opt/cprocsp/bin/amd64/csptest",
                "-sfsign", "-sign",
                "-in", docIn.getAbsolutePath(),
                "-out", docOut.getAbsolutePath(),
                "-my", certSerial,
                "-base64",
                "-add"
        );

        Process processDoc = pbDoc.start();
        int docExit = processDoc.waitFor();
        if (docExit != 0) {
            System.err.println("Ошибка подписи документа, код выхода: " + docExit);
            printStream(processDoc.getErrorStream());
            return;
        }

        String docSignature = new String(Files.readAllBytes(docOut.toPath()), StandardCharsets.UTF_8)
                .replaceAll("\\s+", "");

        docIn.delete();
        docOut.delete();

        // --- 5. Отправляем документ ---
        System.out.println("🔹 Отправляем документ...");

        String bodyJson = String.format(
                "{ \"document_format\":\"MANUAL\", \"product_document\":\"%s\", \"type\":\"%s\", \"signature\":\"%s\" }",
                base64Document,
                typeDocument,
                docSignature
        );

        URL docUrl = new URL(urlCreateDoc);
        HttpsURLConnection con = (HttpsURLConnection) docUrl.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Authorization", "Bearer " + bearerToken);
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = con.getResponseCode();
        System.out.println("Status code: " + responseCode);
        String response = readStream(responseCode >= 400 ? con.getErrorStream() : con.getInputStream());

        if (responseCode >= 400) {
            System.err.println("Ошибка сервера: " + response);
            String msg = extractJsonValue(response, "error_message");
            if (msg != null) System.err.println("Сообщение ошибки: " + msg);
        } else {
            System.out.println("Response: " + response);
        }
    }

    // --- Вспомогательные методы ---

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static void printStream(InputStream is) throws IOException {
        if (is == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) System.err.println(line);
        }
    }

    private static String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        int start = json.indexOf(":", idx) + 1;
        int firstQuote = json.indexOf("\"", start);
        int endQuote = json.indexOf("\"", firstQuote + 1);
        if (firstQuote == -1 || endQuote == -1) return null;
        return json.substring(firstQuote + 1, endQuote);
    }
}
