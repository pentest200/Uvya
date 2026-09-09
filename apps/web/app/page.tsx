export default function Home() {
  return (
    <main className="min-h-screen bg-slate-950 px-6 py-16 text-slate-100">
      <div className="mx-auto flex max-w-3xl flex-col gap-10">
        <header className="space-y-4">
          <p className="text-sm font-semibold uppercase tracking-[0.3em] text-sky-400">
            Uvya
          </p>
          <h1 className="text-4xl font-semibold tracking-tight sm:text-6xl">
            Engineering foundation online.
          </h1>
          <p className="max-w-2xl text-lg leading-8 text-slate-300">
            The web shell is ready for the first product contract. Authentication,
            conversations, and durable messaging will arrive in incremental milestones.
          </p>
        </header>

        <section className="grid gap-4 sm:grid-cols-3" aria-label="Foundation components">
          {["HTTP gateway", "Realtime gateway", "Local infrastructure"].map((component) => (
            <div key={component} className="rounded-2xl border border-slate-800 bg-slate-900 p-5">
              <div className="mb-4 h-2 w-2 rounded-full bg-emerald-400" aria-hidden="true" />
              <h2 className="font-medium">{component}</h2>
              <p className="mt-2 text-sm leading-6 text-slate-400">Bootstrapped and health-checked.</p>
            </div>
          ))}
        </section>
      </div>
    </main>
  );
}
