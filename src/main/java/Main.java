import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class Main {
  public static void main(String[] args) {

    final String command = args[0];

    switch (command) {
      case "init" -> init();
      case "cat-file" -> catFile(args);
      default -> System.out.println("Unknown command: " + command);
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

  public static void catFile(String[] args) {
    Path filepath = Paths.get(String.format("%s/.git/objects/%s/%s", System.getProperty("user.dir"),
        args[2].substring(0, 2), args[2].substring(2)));
    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      byte[] compressedData = Files.readAllBytes(filepath);
      decompress(compressedData, os);
      String text = bytesToString(os.toByteArray());
      System.out.print(text.substring(text.indexOf('\0') + 1));
    } catch (IOException e) {
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
