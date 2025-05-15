import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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
        default -> System.out.println("Unknown command: " + command);
      }
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

  /**
   * tree {tree_sha}
   * {parents}
   * author {author_name} <{author_email}> {author_date_seconds}
   * {author_date_timezone}
   * committer {committer_name} <{committer_email}> {committer_date_seconds}
   * {committer_date_timezone}
   * 
   * {commit message}
   */
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
