package com.aliyun.sample;

import com.aliyun.alimt20181012.Client;
import com.aliyun.alimt20181012.models.TranslateGeneralRequest;
import com.aliyun.alimt20181012.models.TranslateGeneralResponse;
import com.aliyun.teaopenapi.models.Config;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author jdfcc
 */
public class BatchFolderTranslator {

    private static final Pattern CN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");
    private static final String ERROR_LOG = "error.log";

    private static final ExecutorService TRANSLATE_POOL = Executors.newFixedThreadPool(10);
    private static final String TRANS_SOURCE="zh";
    private static final String TRANS_TARGET="en";
   private static final String FOLDER_PATH = "Target_Path";

   private static final String ALI_ACCESS_KEY="ALI_ACCESS_KEY";
   private static final String ALI_SECRET="ALI_SECRET";

    public static Client createClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(ALI_ACCESS_KEY)
                .setAccessKeySecret(ALI_SECRET);
        config.endpoint = "mt.cn-hangzhou.aliyuncs.com";
        return new Client(config);
    }

    public static void main(String[] args) throws Exception {

        Client client = createClient();
        traverseFolder(new File(FOLDER_PATH), client);

        TRANSLATE_POOL.shutdown();
        System.out.println("Complete processing of all documents!");
    }

    private static void traverseFolder(File file, Client client) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File f : children) {
                    traverseFolder(f, client);
                }
            }
        } else {
            processFile(file, client);
        }
    }

    private static void processFile(File file, Client client) {
        String originalContent = null;

        try {
            originalContent = readFile(file);

            System.out.println("\n==============================");
            System.out.println("Start processing files: " + file.getAbsolutePath());
            System.out.println("==============================");

            List<String> words = extractChineseWords(originalContent);

            if (words.isEmpty()) {
                System.out.println("Chinese not recognized, skip this file.\n");
                return;
            }

            System.out.println("[Identified Chinese words]");
            for (String w : words) {
                System.out.println(" - " + w);
            }

            Map<String, String> translatedMap = translateWordsMultiThread(words, client);

            if (translatedMap == null) {
                writeError(file.getName(), "Translation failed (Exception logged)");
                writeFile(file, originalContent);
                return;
            }

            System.out.println("\n[Replacement Process]");
            String newContent = replaceAll(originalContent, translatedMap);

            writeFile(file, newContent);
            System.out.println("File processing complete: " + file.getName());

        } catch (Exception e) {
            writeError(file.getName(), "Handling Exceptions: " + e.getMessage());
            if (originalContent != null) {
                try {
                    writeFile(file, originalContent);
                } catch (IOException ignored) {}
            }
        }
    }

    private static Map<String, String> translateWordsMultiThread(List<String> words, Client client) throws InterruptedException {

        Map<String, Future<String>> futures = new LinkedHashMap<>();

        for (String word : words) {
            futures.put(word, TRANSLATE_POOL.submit(new TranslateTask(word, client)));
        }

        Map<String, String> result = new LinkedHashMap<>();

        for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
            try {
                String translated = entry.getValue().get();
                result.put(entry.getKey(), translated);

                System.out.println(" Translation Results: \"" + entry.getKey() + "\" → \"" + translated + "\"");

            } catch (Exception e) {
                System.err.println("Translation failed: " + entry.getKey());
                writeError("Word translation failed", entry.getKey() + ": " + e.getMessage());
                return null;
            }
        }

        return result;
    }

    /** Single translation task */
    private static class TranslateTask implements Callable<String> {
        private final String word;
        private final Client client;

        TranslateTask(String word, Client client) {
            this.word = word;
            this.client = client;
        }

        @Override
        public String call() throws Exception {
            TranslateGeneralRequest req = new TranslateGeneralRequest()
                    .setFormatType("text")
                    .setSourceLanguage(TRANS_SOURCE)
                    .setTargetLanguage(TRANS_TARGET)
                    .setSourceText(word)
                    .setScene("general");

            TranslateGeneralResponse resp = client.translateGeneral(req);
            return resp.body.data.translated;
        }
    }

    private static List<String> extractChineseWords(String content) {
        Matcher m = CN_PATTERN.matcher(content);
        List<String> result = new ArrayList<>();

        while (m.find()) {
            String w = m.group();
            result.add(w);
        }
        return result;
    }

    private static String replaceAll(String content, Map<String, String> map) {

        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o2.length() - o1.length(); // 长词优先
            }
        });

        for (String cn : keys) {
            String en = map.get(cn);
            if (content.contains(cn)) {
                System.out.println(" replace: \"" + cn + "\" → \"" + en + "\"");
                content = content.replace(cn, en);
            }
        }
        return content;
    }

    private static String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private static void writeFile(File file, String content) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(content);
        writer.close();
    }

    private static synchronized void writeError(String fileName, String reason) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(ERROR_LOG, true));
            bw.write(now() + " | " + fileName + " | " + reason);
            bw.newLine();
            bw.close();
        } catch (IOException ignored) {}
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
