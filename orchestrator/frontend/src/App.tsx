import { Routes, Route, Navigate } from "react-router-dom";
import { LoginPage } from "./pages/LoginPage";
import { DashboardPage } from "./pages/DashboardPage";

/**
 * Each dashboard tab is a real route, so the browser's back button moves
 * between tabs instead of leaving the app. The selected instance is part of
 * the path too, which makes a session link shareable.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/instances" element={<DashboardPage />} />
      <Route path="/runs" element={<DashboardPage />} />
      <Route path="/session" element={<DashboardPage />} />
      <Route path="/session/:resource" element={<DashboardPage />} />
      <Route path="/logs" element={<DashboardPage />} />
      <Route path="/logs/:resource" element={<DashboardPage />} />
      <Route path="*" element={<Navigate to="/instances" replace />} />
    </Routes>
  );
}
