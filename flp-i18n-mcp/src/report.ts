import type { CliEnvelope } from "./cli";

export interface ReportEntry {
  generatedAt: string;
  tool: string;
  module: string | null;
  os: string | null;
  result: CliEnvelope;
}

export class ReportStore {
  private latestEntry: ReportEntry | null = null;

  record(entry: ReportEntry): void {
    this.latestEntry = entry;
  }

  latest(): ReportEntry | null {
    return this.latestEntry;
  }

  clear(): void {
    this.latestEntry = null;
  }
}
