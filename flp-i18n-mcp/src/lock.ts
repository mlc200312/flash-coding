export class ModuleLocks {
  private readonly tails = new Map<string, Promise<void>>();

  async run<T>(key: string, task: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(key) ?? Promise.resolve();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const queued = previous.then(
      () => gate,
      () => gate,
    );
    this.tails.set(key, queued);

    try {
      await previous;
    } catch {
      // previous is never rejected; kept for safety
    }

    try {
      return await task();
    } finally {
      release();
      if (this.tails.get(key) === queued) {
        this.tails.delete(key);
      }
    }
  }

  pendingKeys(): string[] {
    return [...this.tails.keys()];
  }
}
