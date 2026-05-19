package com.elowbe.tools;

public class ToolResult {
	private final String output;
	private final boolean complete;
	private final String finalResponse;

	public ToolResult(String output, boolean complete, String finalResponse) {
		this.output = output == null ? "" : output;
		this.complete = complete;
		this.finalResponse = finalResponse == null ? "" : finalResponse;
	}

	public static ToolResult output(String output) {
		return new ToolResult(output, false, "");
	}

	public static ToolResult complete(String output, String finalResponse) {
		return new ToolResult(output, true, finalResponse);
	}

	public String getOutput() {
		return output;
	}

	public boolean isComplete() {
		return complete;
	}

	public String getFinalResponse() {
		return finalResponse;
	}
}
