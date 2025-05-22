import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Main {
  public static void main(String[] args) {

    final String command = args[0];
    try {
      switch (command) {
        case "init" -> init();
        case "cat-file" -> catFile(args);
        case "hash-object" -> hashObject(args);
        case "ls-tree" -> lsTree(args);
        case "write-tree" -> writeTree(args);
        case "commit-tree" -> commitTree(args);
        case "clone" -> clone(args);
        case "test" -> test(args);
        default -> System.out.println("Unknown command: " + command);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void test(String[] args) {
    byte[] compressedBytes = stringToBytes2(args[args.length - 1]);
    byte[] output = new byte[233];
    Inflater inflater = new Inflater();
    inflater.setInput(compressedBytes);
    try {
      int count = inflater.inflate(output);
      System.out.println("count " + count);
    } catch (DataFormatException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] stringToBytes2(String message) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      for (int i = 0; i < message.length(); i += 2) {
        os.write((byte) Integer.parseInt(message.substring(i, i + 2), 16));
      }
      return os.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void init() {
    final File root = new File(".git");
    new File(root, "objects").mkdirs();
    new File(root, "refs").mkdirs();
    final File head = new File(root, "HEAD");

    try {
      head.createNewFile();
      Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes());
      System.out.println("Initialized git directory");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void catFile(String[] args) throws IOException {
    Path filepath = Paths.get(String.format("%s/.git/objects/%s/%s", System.getProperty("user.dir"),
        args[2].substring(0, 2), args[2].substring(2)));
    byte[] compressedData = Files.readAllBytes(filepath);
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      decompress(compressedData, os);
      String text = bytesToString(os.toByteArray());
      System.out.print(text.substring(text.indexOf('\0') + 1));
    }
  }

  public static void hashObject(String[] args) throws IOException {
    String filename = args[1].equalsIgnoreCase("-w") ? args[2] : args[1];
    String message = createBlob(Paths.get(filename));
    String messageSHA = getHash(message);
    if (args[1].equalsIgnoreCase("-w")) {
      new File(
          String.format("%s/.git/objects/%s", System.getProperty("user.dir"), messageSHA.substring(0, 2)))
          .mkdir();
      try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
        compress(stringToBytes(message), os);
        Files.write(
            Paths.get(String.format("%s/.git/objects/%s/%s", System.getProperty("user.dir"), messageSHA.substring(0, 2),
                messageSHA.substring(2))),
            os.toByteArray());
      }
    }
    System.out.println(messageSHA);
  }

  public static void lsTree(String[] args) throws IOException {
    boolean nameOnly = args[1].equals("--name-only");
    String filename = nameOnly ? args[2] : args[1];
    Path filepath = Paths.get(String.format("%s/.git/objects/%s/%s", System.getProperty("user.dir"),
        filename.substring(0, 2), filename.substring(2)));
    byte[] file = Files.readAllBytes(filepath);
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      decompress(file, os);
      formatTree(os.toByteArray(), nameOnly);
    }
  }

  public static void writeTree(String[] args) throws IOException {
    String tree = createTree(System.getProperty("user.dir"));
    String treeSHA = getHash(tree);
    new File(String.format(".git/objects/%s", treeSHA.substring(0, 2)))
        .mkdir();
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      compress(stringToBytes(tree), os);
      Files.write(
          Paths.get(
              String.format(".git/objects/%s/%s",
                  treeSHA.substring(0, 2),
                  treeSHA.substring(2))),
          os.toByteArray());
    }
    System.out.println(treeSHA);
  }

  public static void commitTree(String[] args) throws IOException {
    StringBuilder body = new StringBuilder();
    body.append(String.format("tree %s\n", args[1]));
    if (args[2].equals("-p")) {
      body.append(String.format("parent %s", args[3]));
    }
    body.append(String.format("author Nicholas nickegonzales99@gmail.com %d\n", System.currentTimeMillis()));
    body.append(String.format("committer Nicholas nickegonzales99@gmail.com %d\n\n", System.currentTimeMillis()));
    body.append(args[args.length - 1]);
    body.append("\n");
    String result = String.format("commit %d\0%s", body.length(), body.toString());
    String sha = getHash(result);
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      compress(stringToBytes(result), os);
      new File(String.format(".git/objects/%s", sha.substring(0, 2)))
          .mkdir();
      Files.write(
          Paths.get(
              String.format(".git/objects/%s/%s", sha.substring(0, 2), sha.substring(2))),
          os.toByteArray());
    }
    System.out.println(sha);
  }

  public static void clone(String[] args) throws IOException {

    final int ARG_LENGTH = 3;
    final int PACK_HEADER_LENGTH = 12;
    final char FIRST_PACK_CHAR = 'P';
    final int NIBBLE_LENGTH = 4;
    final int TYPE_MASK = 0x07;
    final int MSB_BIT_MASK = 0x80;

    if (args.length != ARG_LENGTH) {
      System.out.println("Expected usage clone <remote repo url> <local repo name>");
      return;
    }

    String ref = getHeadRef(args[1]);
    byte[] packFile = getPackFile(args[1], ref);
    int i = indexOf(packFile, 0, (byte) FIRST_PACK_CHAR) + PACK_HEADER_LENGTH;
    byte[] compressedBytes = Arrays.copyOfRange(packFile, i, packFile.length);

    // Debugging info,
    System.out.println("PACKAGE DATA");
    System.out.println("packfile length: " + packFile.length);
    System.out
        .println("Number of Git objects: " + Integer.parseInt(bytesToHex(Arrays.copyOfRange(packFile, i - 4, i)), 16));
    System.out.println(bytesToHex(Arrays.copyOfRange(packFile, i + 8065, i + 8065 + 160)));
    System.out.println();
    // End of debugging section

    String localRepoName = args[2];
    new File(localRepoName).mkdir();
    new File(String.format("%s/.git", localRepoName)).mkdir();
    new File(String.format("%s/.git/objects", localRepoName)).mkdir();

    int offset = 0;
    while (offset < compressedBytes.length) {
      byte head = compressedBytes[offset++];
      int type = (head >> NIBBLE_LENGTH) & TYPE_MASK;
      System.out.println("Type: " + type);
      BigInteger size = BigInteger.valueOf(head & 0x0F);
      boolean firstByte = true;
      while ((head & MSB_BIT_MASK) != 0) {
        head = compressedBytes[offset++];
        size = BigInteger.valueOf(head & 0x7F).shiftLeft(firstByte ? 4 : 7).or(size);
        firstByte = false;
      }
      System.out.println("Object size: " + size.intValue());

      int consumed = 0;
      System.out.println("offset set to " + offset);
      switch (type) {
        case 1 -> consumed = processNonRefObject(compressedBytes, offset, size.intValue(), args[2]);
        case 2 -> consumed = processNonRefObject(compressedBytes, offset, size.intValue(), args[2]);
        case 3 -> consumed = processNonRefObject(compressedBytes, offset, size.intValue(), args[2]);
        case 4 -> consumed = processNonRefObject(compressedBytes, offset, size.intValue(), args[2]);
        // Type 5 object reserved by Git
        // case 6 -> processOFSDelta(inflaterInput, args[2]);
        // case 7 -> processRefDelta(compressedBytes, offset, args[2]);
        default -> throw new RuntimeException("Not a supported Git type. Type " + type);
      }

      System.out.println("Bytes consumed: " + consumed);
      offset += consumed;
      System.out.println("offset set to " + offset);
      System.out.println();
    }
  }

  public static void processRefDelta(byte[] bytes, int offset, String repoName) throws IOException {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      os.write(Arrays.copyOfRange(bytes, offset, offset + 20));

    }
  }

  public static int processNonRefObject(byte[] compressedBytes, int offset, int uncompressedSize, String repoName)
      throws IOException {
    Inflater inflater = new Inflater();
    inflater.setInput(compressedBytes, offset, compressedBytes.length - offset);
    byte[] buffer = new byte[uncompressedSize];
    try {
      int bytesRead = 0;
      while (bytesRead < uncompressedSize) {
        int count = inflater.inflate(buffer, bytesRead, uncompressedSize - bytesRead);
        System.out.println("Count: " + count + " | BytesRead: " + bytesRead);
        if (count <= 0) {
          System.out.println("delta: " + (uncompressedSize - (bytesRead + count)));
          throw new RuntimeException("Not enough bytes in stream");
        }
        bytesRead += count;
      }
      writeToGitFolder(new String(buffer), repoName);
      return (int) inflater.getBytesRead();
    } catch (DataFormatException e) {
      throw new RuntimeException("Failed to read at position " + offset + ".\n Reason given: " + e);
    }
  }

  public static void writeToGitFolder(String message, String repoName) throws IOException {
    String name = getHash(message);
    new File(String.format("%s/.git/objects/%s",
        repoName,
        name.substring(0, 2)))
        .mkdir();
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      compress(message.getBytes(), os);
      Files.write(
          Paths.get(
              String.format("%s/.git/objects/%s/%s",
                  repoName,
                  name.substring(0, 2),
                  name.substring(2))),
          os.toByteArray());
    }
  }

  public static String getHeadRef(String address) throws IOException {
    try {
      URL url = new URI(address + "/info/refs?service=git-upload-pack").toURL();
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      try (InputStream is = connection.getInputStream();
          ByteArrayOutputStream os = new ByteArrayOutputStream()) {
        int b;
        while ((b = is.read()) != -1) {
          os.write((byte) b);
        }
        byte[] buffer = os.toByteArray();
        int i = indexOf(buffer, 0, (byte) '\n') + 9;
        return bytesToString(Arrays.copyOfRange(buffer, i, i + 40));
      }
    } catch (URISyntaxException | MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] getPackFile(String address, String reference) throws IOException {
    try {
      URL url = new URI(address + "/git-upload-pack").toURL();
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/x-git-upload-pack-request");
      connection.setRequestProperty("Accept", "application/x-git-upload-pack-result");
      try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
        out.write(getWantMessage(reference));
        out.flush();
      }
      if (connection.getResponseCode() != 200)
        throw new RuntimeException(
            String.format("Connection failed, received response code: %d %s",
                connection.getResponseCode(),
                connection.getResponseMessage()));
      try (InputStream is = connection.getInputStream();
          ByteArrayOutputStream os = new ByteArrayOutputStream()) {
        int b;
        while ((b = is.read()) != -1) {
          os.write((byte) b);
        }
        return os.toByteArray();
      }
    } catch (URISyntaxException | MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  // TODO: make this dynamic
  public static byte[] getWantMessage(String header) throws IOException {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      String wantLine = "want " + header + " delete-refs side-band-64k quiet ofs-delta agent=java/0.1\n";
      byte[] wantLineBytes = wantLine.getBytes(StandardCharsets.UTF_8);
      os.write(String.format("%04x", wantLineBytes.length + 4).getBytes(StandardCharsets.UTF_8));
      os.write(wantLineBytes);
      os.write("0000".getBytes(StandardCharsets.UTF_8));
      os.write("0009done\n".getBytes(StandardCharsets.UTF_8));
      return os.toByteArray();
    }
  }

  public static String createTree(String pathname) throws IOException {
    ArrayList<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(pathname))) {
      for (Path entry : stream) {
        files.add(entry);
      }
    }
    files.sort((a, b) -> a.compareTo(b));
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        String mode;
        byte[] hash;
        if (file.endsWith(".git"))
          continue;
        else if (Files.isDirectory(file)) {
          mode = "40000";
          hash = getHashAsBytes(createTree(file.toString()));
        } else if (Files.isExecutable(file)) {
          mode = "100755";
          hash = getHashAsBytes(createBlob(file));
        } else if (Files.isSymbolicLink(file)) {
          mode = "120000";
          hash = getHashAsBytes(createBlob(file));
        } else {
          mode = "100644";
          hash = getHashAsBytes(createBlob(file));
        }
        os.write(stringToBytes(String.format("%s %s\0", mode, name)));
        os.write(hash);
      }
      byte[] body = os.toByteArray();
      return String.format("tree %d\0%s", body.length, bytesToString(body));
    }
  }

  public static String createBlob(Path file) throws IOException {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      byte[] text = Files.readAllBytes(file);
      os.write(stringToBytes(String.format("blob %d\0", text.length)));
      os.write(text);
      return bytesToString(os.toByteArray());
    }
  }

  public static void formatTree(byte[] tree, boolean nameOnly) {
    String[][] terms = getObjectsFromTree(tree);
    for (String[] term : terms) {
      if (nameOnly)
        System.out.println(String.format("%s", term[1]));
      else {
        System.out.println(
            String.format("%s %s %s\t%s", term[0], (term[0].equals("40000")) ? "tree" : "blob", term[2], term[1]));
      }
    }
  }

  public static String[][] getObjectsFromTree(byte[] tree) {
    ArrayList<String[]> result = new ArrayList<>();
    int start = indexOf(tree, 0, (byte) 0) + 1;
    for (int i = start; i < tree.length; i = indexOf(tree, i, (byte) 0) + 21) {
      String[] term = new String[3];
      int modeDelineator = indexOf(tree, i, (byte) ' ');
      int shaDelineator = indexOf(tree, i, (byte) 0);
      term[0] = bytesToString(Arrays.copyOfRange(tree, i, modeDelineator));
      term[1] = bytesToString(Arrays.copyOfRange(tree, modeDelineator, shaDelineator)).substring(1);
      term[2] = bytesToHex(Arrays.copyOfRange(tree, shaDelineator, shaDelineator + 21)).substring(2);
      result.add(term);
    }
    result.sort((a, b) -> a[1].compareTo(b[1]));
    return result.toArray(new String[0][0]);
  }

  public static int indexOf(byte[] arr, int start, byte term) {
    for (int i = start; i < arr.length; i++) {
      if (arr[i] == term)
        return i;
    }
    return arr.length;
  }

  public static String getHash(String message) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.update(stringToBytes(message));
      return bytesToHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] getHashAsBytes(String message) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.update(stringToBytes(message));
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public static String bytesToString(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append((char) b);
    }
    return result.toString();
  }

  public static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }

  public static byte[] stringToBytes(String message) {
    byte[] result = new byte[message.length()];
    for (int i = 0; i < message.length(); i++) {
      result[i] = (byte) message.charAt(i);
    }
    return result;
  }

  public static void compress(byte[] file, ByteArrayOutputStream os) {
    Deflater compresser = new Deflater();
    compresser.setInput(file);
    compresser.finish();
    byte[] buffer = new byte[1024];
    while (!compresser.finished()) {
      int count = compresser.deflate(buffer);
      os.write(buffer, 0, count);
    }
    compresser.end();
  }

  public static void decompress(byte[] file, ByteArrayOutputStream os) {
    try {
      Inflater inflater = new Inflater();
      inflater.setInput(file);
      byte[] buffer = new byte[1024];
      while (!inflater.finished()) {
        int count = inflater.inflate(buffer);
        os.write(buffer, 0, count);
      }
      inflater.end();
    } catch (DataFormatException e) {
      throw new RuntimeException(e);
    }
  }
}
