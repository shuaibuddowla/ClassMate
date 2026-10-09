"use client";

import { useState, useMemo, useRef } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Plus,
  FileText,
  Users,
  BarChart2,
  MoreVertical,
  Share2,
  Search,
  CheckCircle,
  Trash2,
  Edit2,
  Sparkles,
  Wallet,
  ArrowUpRight,
  ArrowDownLeft,
  Utensils,
  ShoppingBag,
  Info,
  AlertCircle,
  Check,
  X,
} from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import type { RosterStudent } from "@/lib/batch-fund-parser";
import type { Context } from "./app";
import { Modal, Skeleton, ErrorBox } from "./ui";

export function BatchFund({ ctx, onBack }: { ctx: Context; onBack: () => void }) {
  const qc = useQueryClient();
  const [activeTab, setActiveTab] = useState<"home" | "ledger" | "payers" | "summary">("home");
  const [ledgerFilter, setLedgerFilter] = useState<"all" | "inflow" | "outflow">("all");
  const [ledgerSearch, setLedgerSearch] = useState("");
  const [showAddModal, setShowAddModal] = useState(false);
  const [showEditDescModal, setShowEditDescModal] = useState(false);
  const [selectedTx, setSelectedTx] = useState<Row | null>(null);
  const [copiedToast, setCopiedToast] = useState(false);

  // Queries
  const summary = useQuery({
    queryKey: [ctx.user, ctx.batch, "batch-fund-summary"],
    queryFn: () => rpc<Row>("batch_fund_summary", { target_batch: ctx.batch }),
    enabled: !!ctx.batch,
  });

  const transactions = useQuery({
    queryKey: [ctx.user, ctx.batch, "batch-fund-transactions", ledgerFilter, ledgerSearch],
    queryFn: () =>
      rpc<Row[]>("batch_fund_transactions", {
        target_batch: ctx.batch,
        filter_type: ledgerFilter,
        query_text: ledgerSearch,
        result_offset: 0,
        page_limit: 500,
      }),
    enabled: !!ctx.batch,
  });

  const payers = useQuery({
    queryKey: [ctx.user, ctx.batch, "batch-fund-payers"],
    queryFn: () => rpc<Row[]>("batch_fund_payers", { target_batch: ctx.batch }),
    enabled: !!ctx.batch && activeTab === "payers",
  });

  const isCr = summary.data?.is_cr === true;
  const currentBalance = Number(summary.data?.current_balance || 0);
  const totalCollected = Number(summary.data?.total_collected || 0);
  const totalSpent = Number(summary.data?.total_spent || 0);
  const fundDescription =
    summary.data?.fund_description ||
    "Used for mess, events, jersey, and other batch expenses.";
  const batchName = summary.data?.batch_name || "Batch Fund";
  const university = summary.data?.university || "MBSTU";

  // Share statement
  const handleShareSummary = () => {
    const text =
      `📊 *${batchName} • ${university} Batch Fund Statement*\n\n` +
      `💰 *Current Balance:* ৳ ${currentBalance.toLocaleString()}\n` +
      `📈 *Total Collected:* ৳ ${totalCollected.toLocaleString()}\n` +
      `📉 *Total Spent:* ৳ ${totalSpent.toLocaleString()}\n\n` +
      `ℹ️ _${fundDescription}_\n` +
      `_Updated via ClassMate_`;
    navigator.clipboard.writeText(text);
    setCopiedToast(true);
    setTimeout(() => setCopiedToast(false), 2500);
  };

  return (
    <div className="fund-screen">
      {/* Top Bar */}
      <header className="fund-header">
        <button
          className="icon fund-back-btn"
          aria-label="Back"
          onClick={activeTab === "home" ? onBack : () => setActiveTab("home")}
        >
          <ArrowLeft size={22} />
        </button>
        <div className="fund-title-box">
          <h2>Batch Fund</h2>
          <span className="fund-sub">
            {batchName} • {university}
          </span>
        </div>
        <button
          className="icon fund-action-btn"
          aria-label="Share statement"
          onClick={handleShareSummary}
          title="Share statement"
        >
          <Share2 size={20} />
        </button>
      </header>

      {copiedToast && (
        <div className="fund-toast" role="status">
          <CheckCircle size={16} /> Summary copied to clipboard!
        </div>
      )}

      {/* Hero Balance Card */}
      <section className="fund-hero-card">
        <div className="fund-hero-top">
          <span className="fund-balance-label">Current Balance</span>
          <div className="fund-hero-badge" aria-hidden="true">
            <Wallet size={20} />
          </div>
        </div>
        <div className="fund-hero-amount">
          <span className="taka-symbol">৳</span>{" "}
          {currentBalance.toLocaleString("en-US", { minimumFractionDigits: 0 })}
        </div>
        <div className="fund-hero-meta">
          <span>
            Total Collected: <strong>৳ {totalCollected.toLocaleString()}</strong>
          </span>
          <span className="dot-sep">•</span>
          <span>
            Total Spent: <strong>৳ {totalSpent.toLocaleString()}</strong>
          </span>
        </div>
      </section>

      {/* The 4 Action Tiles */}
      <section className="fund-action-tiles">
        {isCr && (
          <button
            className="fund-tile tile-mint"
            onClick={() => setShowAddModal(true)}
          >
            <div className="fund-tile-icon-circle mint">
              <Plus size={22} />
            </div>
            <strong>Add Fund</strong>
            <span>Record a new deposit</span>
          </button>
        )}

        <button
          className={`fund-tile tile-blue ${activeTab === "ledger" ? "active" : ""}`}
          onClick={() => setActiveTab("ledger")}
        >
          <div className="fund-tile-icon-circle blue">
            <FileText size={22} />
          </div>
          <strong>View Ledger</strong>
          <span>See all transactions</span>
        </button>

        <button
          className={`fund-tile tile-purple ${activeTab === "payers" ? "active" : ""}`}
          onClick={() => setActiveTab("payers")}
        >
          <div className="fund-tile-icon-circle purple">
            <Users size={22} />
          </div>
          <strong>Payers</strong>
          <span>Who has paid and who hasn't</span>
        </button>

        <button
          className={`fund-tile tile-yellow ${activeTab === "summary" ? "active" : ""}`}
          onClick={() => setActiveTab("summary")}
        >
          <div className="fund-tile-icon-circle yellow">
            <BarChart2 size={22} />
          </div>
          <strong>Summary</strong>
          <span>Quick overview & stats</span>
        </button>
      </section>

      {/* Main Content Area based on activeTab */}
      {activeTab === "home" && (
        <>
          {/* Recent Activity */}
          <section className="fund-recent-section">
            <div className="section-header">
              <h3>Recent Activity</h3>
              <button
                className="see-all-btn"
                onClick={() => setActiveTab("ledger")}
              >
                See All ›
              </button>
            </div>

            {transactions.isPending ? (
              <Skeleton />
            ) : !transactions.data?.length ? (
              <div className="fund-empty-recent">
                <Wallet size={36} className="empty-icon" />
                <p>No transactions recorded yet.</p>
                {isCr && (
                  <button
                    className="small-primary-btn"
                    onClick={() => setShowAddModal(true)}
                  >
                    Add first deposit
                  </button>
                )}
              </div>
            ) : (
              <div className="fund-activity-list">
                {transactions.data.slice(0, 5).map((tx) => (
                  <TransactionRow
                    key={tx.id}
                    tx={tx}
                    isCr={isCr}
                    onClick={() => {
                      if (isCr) setSelectedTx(tx);
                    }}
                  />
                ))}
              </div>
            )}
          </section>

          {/* Bottom Card */}
          <section
            className={`fund-bottom-card ${isCr ? "clickable" : ""}`}
            onClick={() => {
              if (isCr) setShowEditDescModal(true);
            }}
          >
            <div className="fund-bottom-icon">
              <Users size={20} />
            </div>
            <div className="fund-bottom-content">
              <h4>Batch Fund</h4>
              <p>{fundDescription}</p>
            </div>
            {isCr && <Edit2 size={16} className="fund-bottom-arrow" />}
          </section>
        </>
      )}

      {/* View: Full Ledger */}
      {activeTab === "ledger" && (
        <section className="fund-subview">
          <div className="fund-subview-nav">
            <button
              className="text-back-btn"
              onClick={() => setActiveTab("home")}
            >
              ← Back to Overview
            </button>
            <h3>All Transactions</h3>
          </div>

          <div className="fund-ledger-toolbar">
            <div className="fund-filter-chips">
              <button
                className={`chip ${ledgerFilter === "all" ? "active" : ""}`}
                onClick={() => setLedgerFilter("all")}
              >
                All
              </button>
              <button
                className={`chip ${ledgerFilter === "inflow" ? "active" : ""}`}
                onClick={() => setLedgerFilter("inflow")}
              >
                <ArrowDownLeft size={14} /> Deposits (+)
              </button>
              <button
                className={`chip ${ledgerFilter === "outflow" ? "active" : ""}`}
                onClick={() => setLedgerFilter("outflow")}
              >
                <ArrowUpRight size={14} /> Expenses (-)
              </button>
            </div>

            <div className="fund-search-bar">
              <Search size={16} />
              <input
                placeholder="Search transaction or student..."
                value={ledgerSearch}
                onChange={(e) => setLedgerSearch(e.target.value)}
              />
            </div>
          </div>

          {transactions.isPending ? (
            <Skeleton />
          ) : !transactions.data?.length ? (
            <div className="fund-empty-recent">
              <p>No matching transactions found.</p>
            </div>
          ) : (
            <div className="fund-activity-list full-ledger">
              {transactions.data.map((tx) => (
                <TransactionRow
                  key={tx.id}
                  tx={tx}
                  isCr={isCr}
                  onClick={() => {
                    if (isCr) setSelectedTx(tx);
                  }}
                />
              ))}
            </div>
          )}
        </section>
      )}

      {/* View: Payers */}
      {activeTab === "payers" && (
        <section className="fund-subview">
          <div className="fund-subview-nav">
            <button
              className="text-back-btn"
              onClick={() => setActiveTab("home")}
            >
              ← Back to Overview
            </button>
            <h3>Classmate Contributions</h3>
          </div>

          <p className="fund-subview-hint">
            Overview of batchmates and their total recorded contributions to
            the batch fund.
          </p>

          {payers.isPending ? (
            <Skeleton />
          ) : !payers.data?.length ? (
            <p className="empty-msg">No student records found.</p>
          ) : (
            <div className="fund-payers-list">
              {payers.data.map((p) => {
                const paid = Number(p.total_paid || 0);
                return (
                  <div className="payer-row" key={p.profile_id}>
                    <div className="payer-avatar">
                      {p.full_name?.charAt(0)?.toUpperCase() || "S"}
                    </div>
                    <div className="payer-info">
                      <h4>{p.full_name}</h4>
                      <span>ID: {p.student_id || "—"}</span>
                    </div>
                    <div className="payer-status">
                      {paid > 0 ? (
                        <span className="paid-badge">
                          + ৳ {paid.toLocaleString()}
                        </span>
                      ) : (
                        <span className="unpaid-badge">No deposit</span>
                      )}
                      {p.payment_count > 0 && (
                        <small className="payer-count">
                          {p.payment_count} payment(s)
                        </small>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}

      {/* View: Summary */}
      {activeTab === "summary" && (
        <section className="fund-subview">
          <div className="fund-subview-nav">
            <button
              className="text-back-btn"
              onClick={() => setActiveTab("home")}
            >
              ← Back to Overview
            </button>
            <h3>Fund Overview & Stats</h3>
          </div>

          <div className="summary-stats-grid">
            <div className="stat-card">
              <span className="stat-label">Net Fund Balance</span>
              <strong className="stat-value text-teal">
                ৳ {currentBalance.toLocaleString()}
              </strong>
            </div>
            <div className="stat-card">
              <span className="stat-label">Total Inflow</span>
              <strong className="stat-value text-green">
                + ৳ {totalCollected.toLocaleString()}
              </strong>
            </div>
            <div className="stat-card">
              <span className="stat-label">Total Outflow</span>
              <strong className="stat-value text-red">
                - ৳ {totalSpent.toLocaleString()}
              </strong>
            </div>
            <div className="stat-card">
              <span className="stat-label">Transactions Count</span>
              <strong className="stat-value">
                {summary.data?.transaction_count || 0}
              </strong>
            </div>
          </div>

          <div className="summary-actions">
            <button
              className="primary wide share-cta"
              onClick={handleShareSummary}
            >
              <Share2 size={18} /> Copy Summary to WhatsApp
            </button>
          </div>
        </section>
      )}

      {/* Modal: Add Fund (AI Smart Paste + Manual) */}
      {showAddModal && (
        <AddFundModal
          batchId={ctx.batch}
          close={() => setShowAddModal(false)}
          onSuccess={() => {
            setShowAddModal(false);
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-summary"],
            });
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-transactions"],
            });
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-payers"],
            });
          }}
        />
      )}

      {/* Modal: Edit or Delete Transaction */}
      {selectedTx && (
        <EditTransactionModal
          tx={selectedTx}
          batchId={ctx.batch}
          close={() => setSelectedTx(null)}
          onSuccess={() => {
            setSelectedTx(null);
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-summary"],
            });
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-transactions"],
            });
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-payers"],
            });
          }}
        />
      )}

      {/* Modal: Edit Fund Description */}
      {showEditDescModal && (
        <EditDescriptionModal
          batchId={ctx.batch}
          current={fundDescription}
          close={() => setShowEditDescModal(false)}
          onSuccess={() => {
            setShowEditDescModal(false);
            qc.invalidateQueries({
              queryKey: [ctx.user, ctx.batch, "batch-fund-summary"],
            });
          }}
        />
      )}
    </div>
  );
}

// Transaction Row Component
function TransactionRow({
  tx,
  isCr,
  onClick,
}: {
  tx: Row;
  isCr: boolean;
  onClick: () => void;
}) {
  const isInflow = tx.type === "inflow";
  const amountNum = Number(tx.amount || 0);

  // Icon / Initial determination
  const titleLower = String(tx.title || "").toLowerCase();
  let iconContent = null;
  let bgClass = "bg-teal-soft";

  if (isInflow) {
    const char = (tx.student_name || tx.title || "D").charAt(0).toUpperCase();
    iconContent = <span>{char}</span>;
    bgClass = "bg-teal-avatar";
  } else if (
    titleLower.includes("market") ||
    titleLower.includes("food") ||
    titleLower.includes("bazar")
  ) {
    iconContent = <Utensils size={18} />;
    bgClass = "bg-red-soft";
  } else if (
    titleLower.includes("supplies") ||
    titleLower.includes("xerox") ||
    titleLower.includes("print")
  ) {
    iconContent = <ShoppingBag size={18} />;
    bgClass = "bg-orange-soft";
  } else {
    iconContent = <Wallet size={18} />;
    bgClass = "bg-blue-soft";
  }

  // Format Date
  const dateStr = tx.transacted_at
    ? new Date(tx.transacted_at).toLocaleDateString("en-US", {
        month: "short",
        day: "numeric",
      })
    : "";

  return (
    <div
      className={`activity-row ${isCr ? "clickable-row" : ""}`}
      onClick={onClick}
    >
      <div className={`activity-avatar ${bgClass}`}>{iconContent}</div>
      <div className="activity-info">
        <h4 className="activity-title">
          {tx.student_name || tx.title}
        </h4>
        <span className="activity-sub">
          {tx.student_name ? tx.title : "From batch fund"}
          {dateStr ? ` • ${dateStr}` : ""}
        </span>
      </div>
      <div className={`activity-amount ${isInflow ? "inflow" : "outflow"}`}>
        {isInflow ? `+ ৳ ${amountNum.toLocaleString()}` : `- ৳ ${amountNum.toLocaleString()}`}
      </div>
    </div>
  );
}

// Modal for Adding Funds (Manual Entry with Instant Student Search)
function AddFundModal({
  batchId,
  close,
  onSuccess,
}: {
  batchId: string;
  close: () => void;
  onSuccess: () => void;
}) {
  const [transType, setTransType] = useState<"inflow" | "outflow">("inflow");
  const [selectedStudent, setSelectedStudent] = useState<RosterStudent | null>(null);
  const [studentSearch, setStudentSearch] = useState("");
  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [successToast, setSuccessToast] = useState<string | null>(null);

  const studentInputRef = useRef<HTMLInputElement>(null);
  const amountInputRef = useRef<HTMLInputElement>(null);

  const commonReasons = [
    "Monthly Fund",
    "Jersey",
    "Tour",
    "Picnic",
    "Farewell",
    "Batch Feast",
  ];

  // Query verified student roster for the active batch
  const rosterQuery = useQuery({
    queryKey: [batchId, "batch-students-roster"],
    queryFn: () =>
      rpc<Row[]>("batch_friends", {
        target_batch: batchId,
        query_text: "",
        result_offset: 0,
      }),
    enabled: !!batchId,
  });

  const roster: RosterStudent[] = useMemo(() => {
    return (rosterQuery.data || [])
      .filter((p) => p.role !== "teacher")
      .map((p) => ({
        id: p.profile_id,
        full_name: p.full_name,
        student_id: p.student_id,
      }));
  }, [rosterQuery.data]);

  // Filter students based on studentSearch
  const filteredStudents = useMemo(() => {
    const q = studentSearch.trim().toLowerCase();
    if (!q) return roster.slice(0, 10);
    return roster
      .filter(
        (s) =>
          s.full_name.toLowerCase().includes(q) ||
          (s.student_id && s.student_id.toLowerCase().includes(q))
      )
      .slice(0, 12);
  }, [roster, studentSearch]);

  const recordTransaction = async (keepOpen = false) => {
    setError(null);
    setSuccessToast(null);

    const numericAmount = parseFloat(amount);
    if (isNaN(numericAmount) || numericAmount <= 0) {
      setError("Please enter a valid amount greater than 0.");
      return;
    }

    if (transType === "inflow") {
      if (!selectedStudent) {
        setError("Please search and select a student from the batch roster.");
        return;
      }
      if (!title.trim()) {
        setError("Please enter the reason of collection.");
        return;
      }
    } else {
      if (!title.trim()) {
        setError("Please enter the expense title or purpose.");
        return;
      }
    }

    setSubmitting(true);
    try {
      await rpc("record_batch_fund_transaction", {
        target_batch: batchId,
        trans_type: transType,
        trans_amount: numericAmount,
        trans_title: title.trim(),
        trans_student_name: transType === "inflow" ? selectedStudent?.full_name : null,
        trans_student_profile_id: transType === "inflow" ? selectedStudent?.id : null,
        trans_date: new Date().toISOString().slice(0, 10),
      });

      onSuccess();

      if (keepOpen) {
        const savedName = selectedStudent?.full_name || "Entry";
        setSuccessToast(`Saved ৳ ${numericAmount.toLocaleString()} for ${savedName}! Enter next student below.`);
        setSelectedStudent(null);
        setStudentSearch("");
        setAmount("");
        setTimeout(() => {
          studentInputRef.current?.focus();
        }, 60);
      } else {
        close();
      }
    } catch (e: any) {
      setError(e.message || "Failed to save transaction.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal title="Record Transaction" close={close}>
      <div className="type-toggle" style={{ marginBottom: 16 }}>
        <button
          type="button"
          className={`type-btn inflow ${transType === "inflow" ? "selected" : ""}`}
          onClick={() => {
            setTransType("inflow");
            setError(null);
          }}
        >
          <ArrowDownLeft size={16} /> Deposit (+)
        </button>
        <button
          type="button"
          className={`type-btn outflow ${transType === "outflow" ? "selected" : ""}`}
          onClick={() => {
            setTransType("outflow");
            setError(null);
          }}
        >
          <ArrowUpRight size={16} /> Expense (-)
        </button>
      </div>

      {error && <ErrorBox error={error} />}
      {successToast && (
        <div className="fund-success-toast">
          <Check size={16} />
          <span>{successToast}</span>
        </div>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          recordTransaction(false);
        }}
        className="manual-entry-form"
      >
        {transType === "inflow" ? (
          <>
            {/* 1. Student Name Selection First */}
            <div className="form-group student-search-group">
              <label>
                Student Name <span className="required-star">*</span>
              </label>

              {selectedStudent ? (
                <div className="selected-student-chip">
                  <div className="selected-student-info">
                    <strong>{selectedStudent.full_name}</strong>
                    {selectedStudent.student_id && (
                      <small>{selectedStudent.student_id}</small>
                    )}
                  </div>
                  <button
                    type="button"
                    className="selected-student-clear-btn"
                    title="Change student"
                    onClick={() => {
                      setSelectedStudent(null);
                      setStudentSearch("");
                      setTimeout(() => studentInputRef.current?.focus(), 50);
                    }}
                  >
                    <X size={16} />
                  </button>
                </div>
              ) : (
                <div className="student-search-box">
                  <Search size={16} className="search-icon" />
                  <input
                    ref={studentInputRef}
                    type="text"
                    placeholder="Type a letter to search name or ID (e.g. Mehedi, 21012)..."
                    value={studentSearch}
                    onFocus={() => setIsSearchOpen(true)}
                    onBlur={() => {
                      setTimeout(() => setIsSearchOpen(false), 220);
                    }}
                    onChange={(e) => {
                      setStudentSearch(e.target.value);
                      setIsSearchOpen(true);
                    }}
                    autoFocus
                  />
                  {studentSearch && (
                    <button
                      type="button"
                      className="search-clear-btn"
                      onClick={() => setStudentSearch("")}
                    >
                      <X size={14} />
                    </button>
                  )}

                  {isSearchOpen && (
                    <div className="student-dropdown-list">
                      {filteredStudents.length > 0 ? (
                        filteredStudents.map((s) => (
                          <button
                            key={s.id}
                            type="button"
                            className="student-dropdown-item"
                            onMouseDown={(e) => {
                              e.preventDefault();
                              setSelectedStudent(s);
                              setStudentSearch("");
                              setIsSearchOpen(false);
                              setError(null);
                              setTimeout(() => amountInputRef.current?.focus(), 60);
                            }}
                          >
                            <span className="dropdown-name">{s.full_name}</span>
                            {s.student_id && (
                              <span className="dropdown-id">{s.student_id}</span>
                            )}
                          </button>
                        ))
                      ) : (
                        <div className="dropdown-empty">
                          No batchmates found matching "{studentSearch}"
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* 2. Money Input Second */}
            <div className="form-group">
              <label>
                Amount (৳) <span className="required-star">*</span>
              </label>
              <input
                ref={amountInputRef}
                type="number"
                step="any"
                placeholder="e.g. 500"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </div>

            {/* 3. Reason of Collection Third */}
            <div className="form-group">
              <label>
                Reason of Collection <span className="required-star">*</span>
              </label>
              <input
                type="text"
                placeholder="e.g. Jersey fee, Monthly mess, Tour"
                required
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
              <div className="quick-reason-chips">
                {commonReasons.map((r) => (
                  <button
                    key={r}
                    type="button"
                    className={`reason-chip ${title === r ? "active" : ""}`}
                    onClick={() => setTitle(r)}
                  >
                    {r}
                  </button>
                ))}
              </div>
            </div>

            {/* Action buttons */}
            <div className="add-fund-actions">
              <button
                type="button"
                className="secondary"
                disabled={submitting}
                onClick={() => recordTransaction(true)}
              >
                Save &amp; Add Another
              </button>
              <button
                type="submit"
                className="primary"
                disabled={submitting}
              >
                {submitting ? "Saving..." : "Save Transaction"}
              </button>
            </div>
          </>
        ) : (
          <>
            {/* Expense Outflow Form */}
            <div className="form-group">
              <label>
                Expense Purpose / Title <span className="required-star">*</span>
              </label>
              <input
                type="text"
                placeholder="e.g. Market (Food & supplies), Banner print"
                required
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                autoFocus
              />
            </div>

            <div className="form-group">
              <label>
                Amount (৳) <span className="required-star">*</span>
              </label>
              <input
                type="number"
                step="any"
                placeholder="e.g. 2250"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </div>

            <button
              type="submit"
              className="primary wide"
              disabled={submitting}
            >
              {submitting ? "Saving..." : "Record Expense"}
            </button>
          </>
        )}
      </form>
    </Modal>
  );
}

// Modal for Editing or Deleting a Transaction
function EditTransactionModal({
  tx,
  batchId,
  close,
  onSuccess,
}: {
  tx: Row;
  batchId: string;
  close: () => void;
  onSuccess: () => void;
}) {
  const [type, setType] = useState<"inflow" | "outflow">(tx.type);
  const [amount, setAmount] = useState(String(tx.amount || ""));
  const [title, setTitle] = useState(tx.title || "");
  const [studentProfileId, setStudentProfileId] = useState(
    tx.student_profile_id || ""
  );
  const [studentName, setStudentName] = useState(tx.student_name || "");
  const [date, setDate] = useState(tx.transacted_at || "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rosterQuery = useQuery({
    queryKey: [batchId, "batch-students-roster"],
    queryFn: () =>
      rpc<Row[]>("batch_friends", {
        target_batch: batchId,
        query_text: "",
        result_offset: 0,
      }),
    enabled: !!batchId,
  });

  const roster: RosterStudent[] = useMemo(() => {
    return (rosterQuery.data || [])
      .filter((p) => p.role !== "teacher")
      .map((p) => ({
        id: p.profile_id,
        full_name: p.full_name,
        student_id: p.student_id,
      }));
  }, [rosterQuery.data]);

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await rpc("update_batch_fund_transaction", {
        trans_id: tx.id,
        trans_type: type,
        trans_amount: parseFloat(amount),
        trans_title: title.trim(),
        trans_student_name: studentName.trim() || null,
        trans_date: date,
        trans_student_profile_id: studentProfileId || null,
      });
      onSuccess();
    } catch (err: any) {
      setError(err.message || "Failed to update transaction.");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!confirm("Are you sure you want to delete this transaction?")) return;
    setBusy(true);
    setError(null);
    try {
      await rpc("delete_batch_fund_transaction", { trans_id: tx.id });
      onSuccess();
    } catch (err: any) {
      setError(err.message || "Failed to delete transaction.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Edit Transaction" close={close}>
      {error && <ErrorBox error={error} />}
      <form onSubmit={handleUpdate} className="manual-entry-form">
        <div className="form-group">
          <label>Type</label>
          <div className="type-toggle">
            <button
              type="button"
              className={`type-btn inflow ${type === "inflow" ? "selected" : ""}`}
              onClick={() => setType("inflow")}
            >
              Deposit (+)
            </button>
            <button
              type="button"
              className={`type-btn outflow ${type === "outflow" ? "selected" : ""}`}
              onClick={() => setType("outflow")}
            >
              Expense (-)
            </button>
          </div>
        </div>

        <div className="form-group">
          <label>Amount (৳)</label>
          <input
            type="number"
            step="any"
            required
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </div>

        <div className="form-group">
          <label>Title</label>
          <input
            type="text"
            required
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </div>

        {type === "inflow" && (
          <>
            <div className="form-group">
              <label>Link to Batchmate</label>
              <select
                className="preview-select-student"
                value={studentProfileId}
                onChange={(e) => {
                  const val = e.target.value;
                  setStudentProfileId(val);
                  const match = roster.find((s) => s.id === val);
                  if (match) {
                    setStudentName(match.full_name);
                  }
                }}
              >
                <option value="">-- None / Custom Name --</option>
                {roster.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.full_name} {s.student_id ? `(${s.student_id})` : ""}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label>Student Name</label>
              <input
                type="text"
                value={studentName}
                onChange={(e) => setStudentName(e.target.value)}
              />
            </div>
          </>
        )}

        <div className="form-group">
          <label>Date</label>
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </div>

        <div className="actions-row">
          <button
            type="button"
            className="danger"
            disabled={busy}
            onClick={handleDelete}
          >
            <Trash2 size={16} /> Delete
          </button>
          <button type="submit" className="primary" disabled={busy}>
            {busy ? "Saving..." : "Save Changes"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// Modal for Editing Fund Description
function EditDescriptionModal({
  batchId,
  current,
  close,
  onSuccess,
}: {
  batchId: string;
  current: string;
  close: () => void;
  onSuccess: () => void;
}) {
  const [desc, setDesc] = useState(current);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await rpc("update_batch_fund_description", {
        target_batch: batchId,
        new_description: desc.trim(),
      });
      onSuccess();
    } catch (err: any) {
      setError(err.message || "Failed to update description.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Edit Batch Fund Description" close={close}>
      {error && <ErrorBox error={error} />}
      <form onSubmit={handleSave}>
        <p className="ai-paste-hint">
          Explain what your batch fund is used for (e.g. mess bills, jersey,
          picnic, or printing fees).
        </p>
        <textarea
          rows={3}
          required
          value={desc}
          onChange={(e) => setDesc(e.target.value)}
          className="ai-paste-textarea"
        />
        <button type="submit" className="primary wide" disabled={busy}>
          {busy ? "Saving..." : "Save Description"}
        </button>
      </form>
    </Modal>
  );
}
