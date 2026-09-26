import java.io.File;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** E6-T02 evidence: page count, page size and the text head of the D12 agenda PDF (PDFBox 3, as the api renders it). */
public class PdfInfo {
    public static void main(String[] args) throws Exception {
        try (var pdf = Loader.loadPDF(new File(args[0]))) {
            var box = pdf.getPage(0).getMediaBox();
            System.out.printf("pages=%d width=%.1fpt height=%.1fpt %s%n", pdf.getNumberOfPages(), box.getWidth(), box.getHeight(),
                    box.getWidth() > box.getHeight() ? "LANDSCAPE" : "PORTRAIT");
            var lines = new PDFTextStripper().getText(pdf).lines().limit(Integer.parseInt(args.length > 1 ? args[1] : "25")).toList();
            lines.forEach(line -> System.out.println("  | " + line));
        }
    }
}
