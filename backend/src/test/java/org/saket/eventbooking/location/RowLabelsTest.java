package org.saket.eventbooking.location;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.location.service.RowLabels;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowLabelsTest {

    @Test
    void generatesSpreadsheetStyleLabels() {
        assertThat(RowLabels.of(0)).isEqualTo("A");
        assertThat(RowLabels.of(25)).isEqualTo("Z");
        assertThat(RowLabels.of(26)).isEqualTo("AA");
        assertThat(RowLabels.of(27)).isEqualTo("AB");
        assertThat(RowLabels.of(51)).isEqualTo("AZ");
        assertThat(RowLabels.of(52)).isEqualTo("BA");
        assertThat(RowLabels.of(701)).isEqualTo("ZZ");
        assertThat(RowLabels.of(702)).isEqualTo("AAA");
    }

    @Test
    void orderingPutsZBeforeAa() {
        List<String> labels = new ArrayList<>(List.of("AA", "B", "Z", "A", "AB"));
        labels.sort(RowLabels.ORDER);
        assertThat(labels).containsExactly("A", "B", "Z", "AA", "AB");
    }

    @Test
    void rejectsNegativeIndex() {
        assertThatThrownBy(() -> RowLabels.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
