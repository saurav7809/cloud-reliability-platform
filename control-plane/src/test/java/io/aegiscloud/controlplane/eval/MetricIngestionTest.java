package io.aegiscloud.controlplane.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing what Prometheus actually returns, including the shapes that must be refused. */
class MetricIngestionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<Double> parse(String json) throws Exception {
        return MetricIngestion.firstValue(MAPPER.readTree(json));
    }

    @Test
    @DisplayName("a single-series vector yields its value")
    void singleSeriesVector() throws Exception {
        Optional<Double> value = parse("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"auth"},"value":[1756700000.1,"42.5"]}
                ]}}
                """);

        assertThat(value).contains(42.5);
    }

    @Test
    @DisplayName("a scalar result yields its value")
    void scalarResult() throws Exception {
        assertThat(parse("""
                {"status":"success","data":{"resultType":"scalar","result":[1756700000.1,"7"]}}
                """)).contains(7.0);
    }

    @Test
    @DisplayName("a multi-series vector is refused rather than resolved to its first element")
    void multiSeriesVectorIsRefused() throws Exception {
        // Three series means the query did not identify one thing. Storing the first
        // would attribute one pod's number to the whole target, and nothing
        // downstream would ever reveal that it had happened.
        assertThat(parse("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"pod":"a"},"value":[1,"1"]},
                  {"metric":{"pod":"b"},"value":[1,"2"]},
                  {"metric":{"pod":"c"},"value":[1,"3"]}
                ]}}
                """)).isEmpty();
    }

    @Test
    @DisplayName("an empty result is empty, not zero")
    void emptyResultIsAbsent() throws Exception {
        assertThat(parse("""
                {"status":"success","data":{"resultType":"vector","result":[]}}
                """)).isEmpty();
    }

    @Test
    @DisplayName("NaN from Prometheus is refused")
    void nonFiniteIsRefused() throws Exception {
        // Prometheus returns NaN for things like a rate over no samples. Stored, it
        // poisons every average and percentile computed from the series afterwards.
        assertThat(parse("""
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{},"value":[1,"NaN"]}
                ]}}
                """)).isEmpty();
    }

    @Test
    @DisplayName("a matrix result is refused: a range is not an instant reading")
    void matrixIsRefused() throws Exception {
        assertThat(parse("""
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{},"values":[[1,"1"],[2,"2"]]}
                ]}}
                """)).isEmpty();
    }

    @Test
    @DisplayName("an error response yields nothing rather than throwing")
    void errorResponseIsAbsent() throws Exception {
        assertThat(parse("""
                {"status":"error","errorType":"bad_data","error":"parse error"}
                """)).isEmpty();
    }
}
