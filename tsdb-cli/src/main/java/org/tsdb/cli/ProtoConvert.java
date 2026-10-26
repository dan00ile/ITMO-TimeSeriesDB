package org.tsdb.cli;

import java.util.ArrayList;
import java.util.List;

import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// model → proto для клиента. Proto-классы — полными именами: Label, Matcher совпадают с org.tsdb.model.
// Сервер делает обратное в своём ProtoMapper; CLI от tsdb-server не зависит.
final class ProtoConvert {
    private ProtoConvert() {
    }

    static List<org.tsdb.proto.Label> labels(Labels labels) {
        List<org.tsdb.proto.Label> out = new ArrayList<>(labels.labels().size());
        for (Label l : labels.labels()) {
            out.add(org.tsdb.proto.Label.newBuilder().setName(l.name()).setValue(l.value()).build());
        }
        return out;
    }

    static Labels labels(List<org.tsdb.proto.Label> labels) {
        List<Label> out = new ArrayList<>(labels.size());
        for (org.tsdb.proto.Label l : labels) {
            out.add(new Label(l.getName(), l.getValue()));
        }
        return new Labels(out);
    }

    static org.tsdb.proto.Matcher matcher(Matcher m) {
        var b = org.tsdb.proto.Matcher.newBuilder().setName(m.name());
        switch (m) {
            case Matcher.Eq eq -> b.setType(org.tsdb.proto.Matcher.Type.EQ).setValue(eq.value());
            case Matcher.NotEq ne -> b.setType(org.tsdb.proto.Matcher.Type.NEQ).setValue(ne.value());
            case Matcher.Regex re -> b.setType(org.tsdb.proto.Matcher.Type.RE).setValue(re.pattern());
            case Matcher.NotRegex nr -> b.setType(org.tsdb.proto.Matcher.Type.NRE).setValue(nr.pattern());
        }
        return b.build();
    }
}
