import {useUser} from "@clerk/clerk-react";
import Navbar from "../components/Navbar";
import SideMenu from "../components/SideMenu";

const DashboardLayout = ({children, activeMenu}) =>{
    const {user}= useUser();

    return(
        <div className="overflow-x-hidden">
            {/* Nav bar */}
            <Navbar activeMenu={activeMenu}/>
            {user && (
                <div className="flex overflow-x-hidden">
                    <div className="max-[1080px]:hidden">
                        {/* side menu */}
                        <SideMenu activeMenu={activeMenu}/>
                    </div>
                    <div className="flex-1 px-5 min-w-0 overflow-x-hidden">{children}</div>
                </div>
            )}
        </div>
    )
}

export default DashboardLayout;