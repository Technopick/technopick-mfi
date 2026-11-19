import type { RouteObject } from "react-router";
import { Layout } from "./components/Layout";
import { ApplicationPage, ApplicationsPage, NewApplicationPage } from "./pages/ApplicationsPage";
import { CustomerPage, CustomersPage, NewCustomerPage } from "./pages/CustomersPage";
import { DisbursementsPage } from "./pages/DisbursementsPage";
import { EndOfDayPage } from "./pages/EndOfDayPage";
import { ManualJournalsPage, PeriodsPage } from "./pages/FinancePages";
import { AccountActivityPage, JournalEntryPage, TrialBalancePage } from "./pages/LedgerPages";
import { LoanSchedulePage } from "./pages/LoanSchedulePage";
import { MpesaPage } from "./pages/MpesaPage";
import { PortfolioPage } from "./pages/PortfolioPage";
import { ReconciliationPage } from "./pages/ReconciliationPage";
import { WriteOffsPage } from "./pages/WriteOffsPage";
import { AccountPage, TellerPage } from "./pages/SavingsPages";
import { SuspensePage } from "./pages/SuspensePage";
import { WorklistPage } from "./pages/WorklistPage";

export const routes: RouteObject[] = [
  {
    path: "/",
    element: <Layout />,
    children: [
      { index: true, element: <WorklistPage /> },
      { path: "customers", element: <CustomersPage /> },
      { path: "customers/new", element: <NewCustomerPage /> },
      { path: "customers/:number", element: <CustomerPage /> },
      { path: "accounts/:number", element: <AccountPage /> },
      { path: "teller", element: <TellerPage /> },
      { path: "applications", element: <ApplicationsPage /> },
      { path: "applications/new", element: <NewApplicationPage /> },
      { path: "applications/:id", element: <ApplicationPage /> },
      { path: "disbursements", element: <DisbursementsPage /> },
      { path: "mpesa", element: <MpesaPage /> },
      { path: "suspense", element: <SuspensePage /> },
      { path: "end-of-day", element: <EndOfDayPage /> },
      { path: "portfolio", element: <PortfolioPage /> },
      { path: "ledger", element: <TrialBalancePage /> },
      { path: "ledger/reconciliation", element: <ReconciliationPage /> },
      { path: "ledger/journals", element: <ManualJournalsPage /> },
      { path: "ledger/periods", element: <PeriodsPage /> },
      { path: "ledger/accounts/:code", element: <AccountActivityPage /> },
      { path: "ledger/entries/:id", element: <JournalEntryPage /> },
      { path: "write-offs", element: <WriteOffsPage /> },
      { path: "loans", element: <LoanSchedulePage /> },
      { path: "loans/:number", element: <LoanSchedulePage /> },
      { path: "*", element: <p className="empty">Page not found.</p> },
    ],
  },
];
