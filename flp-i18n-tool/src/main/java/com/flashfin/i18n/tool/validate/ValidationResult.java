package com.flashfin.i18n.tool.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {

    private final List<ValidationIssue> issues;
    private final int keysChecked;

    public ValidationResult(List<ValidationIssue> issues, int keysChecked) {
        this.issues = Collections.unmodifiableList(new ArrayList<ValidationIssue>(issues));
        this.keysChecked = keysChecked;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public int getKeysChecked() {
        return keysChecked;
    }

    public int countBySeverity(String severity) {
        int count = 0;
        for (ValidationIssue issue : issues) {
            if (severity.equals(issue.getSeverity())) {
                count++;
            }
        }
        return count;
    }

    public boolean isValid() {
        return countBySeverity(ValidationIssueSeverity.ERROR) == 0;
    }
}
