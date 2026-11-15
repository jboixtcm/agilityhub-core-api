import com.agilityhub.core.clubs.statistics.application.*;
public class VerifyFcagRemote {
    public static void main(String[] args) {
        String source = "https://app.fcagility.cat/federated/classification/division/128";
        try {
            var html = new FcagSourceClient().fetch(source);
            var parsed = new FcagStandingsParser().parse(html, source);
            System.out.println("FCAG server adapter: HTTP fetch + parse succeeded; " + parsed.entries().size() + " rows; season " + parsed.season());
        } catch (com.agilityhub.core.shared.domain.ApiException failure) {
            System.out.println("FCAG server adapter: " + failure.code() + " " + failure.details());
            System.exit(1);
        }
    }
}
