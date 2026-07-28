package com.eduin.onboarding.authenticity.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AnalysisDetail {

    private String analyzer;
    private double score;
    private boolean passed;
    private String verdict;
    private List<String> findings;
    private List<String> warnings;
}
