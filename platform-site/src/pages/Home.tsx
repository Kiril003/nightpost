import { Navigate, useSearchParams } from 'react-router-dom';
import HomeTop from './HomeTop';

export default function Home() {
  const [params] = useSearchParams();
  if (params.has('v')) return <Navigate to="/" replace />;
  return <HomeTop />;
}
