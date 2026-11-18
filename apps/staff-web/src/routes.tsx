import type { RouteObject } from "react-router";
import { Layout } from "./components/Layout";
import { ApplicationPage, ApplicationsPage, NewApplicationPage } from "./pages/ApplicationsPage";
import { CustomerPage, CustomersPage, NewCustomerPage } from "./pages/CustomersPage";
import { DisbursementsPage } from "./pages/DisbursementsPage";
import { LoanSchedulePage } from "./pages/LoanSchedulePage";
import { MpesaPage } from "./pages/MpesaPage";
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
      { path: "applications", element: <ApplicationsPage /> },
      { path: "applications/new", element: <NewApplicationPage /> },
      { path: "applications/:id", element: <ApplicationPage /> },
      { path: "disbursements", element: <DisbursementsPage /> },
      { path: "mpesa", element: <MpesaPage /> },
      { path: "suspense", element: <SuspensePage /> },
      { path: "loans", element: <LoanSchedulePage /> },
      { path: "loans/:number", element: <LoanSchedulePage /> },
      { path: "*", element: <p className="empty">Page not found.</p> },
    ],
  },
];
